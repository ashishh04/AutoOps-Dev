package com.intertec.autoops.agent.loop;

import com.intertec.autoops.agent.modelsdk.ModelVendor;
import com.intertec.autoops.agent.modelsdk.bedrock.BedrockClientFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS Bedrock, through the Converse API.
 *
 * <p>Converse rather than {@code InvokeModel}: InvokeModel takes each model
 * family's own raw request body, so tool calling there would mean a different
 * payload shape per family behind one vendor. Converse is the unified surface
 * and hands back structured {@code toolUse} blocks whatever the underlying
 * model is.
 *
 * <p>Bedrock's shape is closest to Anthropic's - tool results ride in a USER
 * message, all together - which is unsurprising given where Converse came
 * from. Unlike the OpenAI family it does have a first-class error signal on a
 * tool result ({@link ToolResultStatus#ERROR}), so failures do not need a text
 * prefix to be legible.
 *
 * <p>JSON here is {@link Document}, not a map or a string, so the schema and
 * the arguments are converted in both directions below.
 */
@Component
public class BedrockChatModel implements ChatModel {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BedrockChatModel.class);

    @Override
    public boolean supports(ModelVendor vendor) {
        return vendor == ModelVendor.BEDROCK;
    }

    @Override
    public ChatResponse chat(Request request) {
        BedrockRuntimeClient client = BedrockClientFactory.create(request.credentials());
        String region = request.credentials().orElse("region", "");
        try {
            return toChatResponse(converse(client, request, request.model()));
        } catch (ValidationException ex) {
            // The newer Anthropic models cannot be invoked by their bare id at
            // all. Bedrock answers "Invocation of model ID X with on-demand
            // throughput isn't supported. Retry with an inference profile",
            // which is a real instruction rather than a failure — the same
            // model is reachable through a cross-region inference profile whose
            // id is the bare one with a geography prefix.
            //
            // Retried rather than prefixed up front, because the two id forms
            // are not interchangeable: the older models this platform also runs
            // (deepseek, Claude 3 Haiku) have no profile and fail if given one.
            // Asking Bedrock which it wants is cheaper than keeping a list of
            // which models moved, and it stays correct when the next one does.
            //
            // Costs one rejected call, once, on a validation error that burns
            // no tokens.
            String profileId = inferenceProfileId(request.model(), region);
            if (profileId == null) {
                throw explain(ex, request.model(), region);
            }
            log.info("Bedrock refused the bare model id {}; retrying as inference profile {}",
                    request.model(), profileId);
            try {
                return toChatResponse(converse(client, request, profileId));
            } catch (ValidationException retry) {
                throw explain(retry, request.model(), region);
            }
        }
    }

    private ConverseResponse converse(BedrockRuntimeClient client, Request request,
                                      String modelId) {
        ConverseRequest.Builder converse = ConverseRequest.builder()
                .modelId(modelId)
                .messages(toMessages(request.messages()))
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(request.maxTokens())
                        .build());

        if (request.system() != null && !request.system().isBlank()) {
            converse.system(software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock
                    .fromText(request.system()));
        }
        if (!request.tools().isEmpty()) {
            converse.toolConfig(ToolConfiguration.builder()
                    .tools(request.tools().stream().map(this::toTool).toList())
                    .build());
        }
        return client.converse(converse.build());
    }

    /**
     * The cross-region inference profile id for a bare model id, or null.
     *
     * <p>Null means "do not retry": the id already carries a prefix, the region
     * is unknown, or it sits in a geography AWS publishes no profile prefix for.
     * Guessing one would turn a clear "this model needs a profile" into a
     * confusing "that profile does not exist", which is further from the truth
     * rather than closer to it.
     */
    static String inferenceProfileId(String modelId, String region) {
        if (modelId == null || modelId.isBlank() || region == null || region.isBlank()) {
            return null;
        }
        String prefix = profilePrefix(region);
        if (prefix == null || modelId.startsWith(prefix + ".")) {
            return null;
        }
        return prefix + "." + modelId;
    }

    /**
     * The four geographies AWS actually publishes profile prefixes for.
     *
     * <p>An unrecognised region returns null rather than a guess derived from
     * its first segment — {@code me-south-1} would become {@code me.}, which is
     * not a thing, and the resulting error would send whoever reads it looking
     * for a profile that was never going to exist.
     */
    private static String profilePrefix(String region) {
        String r = region.toLowerCase(java.util.Locale.ROOT).trim();
        if (r.startsWith("us-gov-")) {
            return "us-gov";
        }
        if (r.startsWith("us-")) {
            return "us";
        }
        if (r.startsWith("eu-")) {
            return "eu";
        }
        if (r.startsWith("ap-")) {
            return "apac";
        }
        return null;
    }

    /**
     * Bedrock's own message, plus the part a reader needs and it omits: which
     * region this was tried in, and that the id offered in the console may
     * simply not be invocable there.
     */
    private static RuntimeException explain(ValidationException ex, String modelId,
                                            String region) {
        String detail = ex.getMessage() == null ? "" : ex.getMessage();
        if (detail.toLowerCase(java.util.Locale.ROOT).contains("inference profile")) {
            return new IllegalStateException(
                    "Bedrock will not run \"" + modelId + "\" in "
                            + (region.isBlank() ? "this region" : region)
                            + " by its plain model id, and no cross-region inference profile "
                            + "for it is available there. Choose a different model for this "
                            + "agent, or enable the model in a region that offers one. "
                            + "(Bedrock said: " + detail + ")", ex);
        }
        return ex;
    }

    // ----------------------------------------------------------- request ---

    private List<Message> toMessages(List<ChatMessage> messages) {
        List<Message> out = new ArrayList<>();
        for (ChatMessage message : messages) {
            switch (message) {
                case ChatMessage.User user -> out.add(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(user.text()))
                        .build());

                case ChatMessage.Assistant assistant -> {
                    List<ContentBlock> blocks = new ArrayList<>();
                    if (assistant.text() != null && !assistant.text().isBlank()) {
                        blocks.add(ContentBlock.fromText(assistant.text()));
                    }
                    for (ToolCall call : assistant.toolCalls()) {
                        blocks.add(ContentBlock.fromToolUse(ToolUseBlock.builder()
                                .toolUseId(call.id())
                                .name(call.name())
                                .input(toDocument(call.arguments()))
                                .build()));
                    }
                    out.add(Message.builder()
                            .role(ConversationRole.ASSISTANT)
                            .content(blocks)
                            .build());
                }

                // As on Anthropic: one user message carrying every result.
                case ChatMessage.ToolResults toolResults -> {
                    List<ContentBlock> blocks = new ArrayList<>();
                    for (ToolResult result : toolResults.results()) {
                        blocks.add(ContentBlock.fromToolResult(ToolResultBlock.builder()
                                .toolUseId(result.toolCallId())
                                .content(ToolResultContentBlock.fromText(result.content()))
                                .status(result.isError() ? ToolResultStatus.ERROR : ToolResultStatus.SUCCESS)
                                .build()));
                    }
                    out.add(Message.builder()
                            .role(ConversationRole.USER)
                            .content(blocks)
                            .build());
                }
            }
        }
        return out;
    }

    private Tool toTool(ToolSpec spec) {
        return Tool.fromToolSpec(ToolSpecification.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(ToolInputSchema.fromJson(toDocument(spec.inputSchema())))
                .build());
    }

    // ---------------------------------------------------------- response ---

    private ChatResponse toChatResponse(ConverseResponse response) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();

        if (response.output() != null && response.output().message() != null) {
            for (ContentBlock block : response.output().message().content()) {
                if (block.text() != null) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(block.text());
                }
                if (block.toolUse() != null) {
                    ToolUseBlock use = block.toolUse();
                    calls.add(new ToolCall(use.toolUseId(), use.name(), fromDocument(use.input())));
                }
            }
        }

        long promptTokens = response.usage() == null ? 0 : response.usage().inputTokens();
        long completionTokens = response.usage() == null ? 0 : response.usage().outputTokens();

        return new ChatResponse(text.toString(), calls, stopReason(response),
                promptTokens, completionTokens);
    }

    private ChatResponse.StopReason stopReason(ConverseResponse response) {
        String reason = response.stopReasonAsString() == null
                ? "" : response.stopReasonAsString().toLowerCase();
        return switch (reason) {
            case "tool_use" -> ChatResponse.StopReason.TOOL_CALLS;
            case "end_turn", "stop_sequence" -> ChatResponse.StopReason.END_TURN;
            case "max_tokens" -> ChatResponse.StopReason.MAX_TOKENS;
            case "content_filtered", "guardrail_intervened" -> ChatResponse.StopReason.REFUSAL;
            default -> ChatResponse.StopReason.OTHER;
        };
    }

    // ------------------------------------------------------ JSON <-> doc ---

    @SuppressWarnings("unchecked")
    private Document toDocument(Object value) {
        if (value == null) {
            return Document.fromNull();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Document> fields = new LinkedHashMap<>();
            map.forEach((key, item) -> fields.put(String.valueOf(key), toDocument(item)));
            return Document.fromMap(fields);
        }
        if (value instanceof List<?> list) {
            return Document.fromList(list.stream().map(this::toDocument).toList());
        }
        if (value instanceof Boolean bool) {
            return Document.fromBoolean(bool);
        }
        if (value instanceof Integer i) {
            return Document.fromNumber(i);
        }
        if (value instanceof Long l) {
            return Document.fromNumber(l);
        }
        if (value instanceof Number number) {
            return Document.fromNumber(number.doubleValue());
        }
        return Document.fromString(String.valueOf(value));
    }

    private Map<String, Object> fromDocument(Document document) {
        if (document == null || !document.isMap()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        document.asMap().forEach((key, value) -> out.put(key, unwrap(value)));
        return out;
    }

    private Object unwrap(Document document) {
        if (document == null || document.isNull()) {
            return null;
        }
        if (document.isMap()) {
            return fromDocument(document);
        }
        if (document.isList()) {
            return document.asList().stream().map(this::unwrap).toList();
        }
        if (document.isBoolean()) {
            return document.asBoolean();
        }
        if (document.isNumber()) {
            return document.asNumber().doubleValue();
        }
        return document.asString();
    }
}
