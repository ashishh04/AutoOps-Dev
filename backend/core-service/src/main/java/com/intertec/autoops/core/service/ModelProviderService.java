package com.intertec.autoops.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intertec.autoops.core.domain.ModelDeployment;
import com.intertec.autoops.core.domain.ModelProvider;
import com.intertec.autoops.core.domain.ModelProvider.Kind;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.repo.ModelDeploymentRepository;
import com.intertec.autoops.core.repo.ModelProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Set;

/**
 * Bring-your-own-key model providers. Credentials are AES-GCM encrypted at
 * rest with the same {@link CredentialCrypto} the cloud integrations use, and
 * are never returned to a client — not masked, not truncated, not at all.
 *
 * <p>One exception, and it is not a client: {@link #resolveForModel} hands the
 * decrypted config to agent-service over the internal-token channel, because
 * that service has to make a real call to the vendor and this service holds
 * the only key. Nothing on {@code /api} can reach it.
 *
 * <p>"Test" performs a REAL call against the vendor (see
 * {@link ModelProviderProbe}) and records the outcome on the row. A provider
 * that has never been tested says exactly that; nothing here ever reports a
 * green light it did not earn.
 */
@Service
public class ModelProviderService {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderService.class);

    private final ModelProviderRepository providerRepository;
    private final ModelDeploymentRepository deploymentRepository;
    private final SubscriptionGate gate;
    private final CredentialCrypto crypto;
    private final ModelProviderProbe probe;
    private final ObjectMapper objectMapper;

    public ModelProviderService(ModelProviderRepository providerRepository,
                                ModelDeploymentRepository deploymentRepository,
                                SubscriptionGate gate, CredentialCrypto crypto,
                                ModelProviderProbe probe, ObjectMapper objectMapper) {
        this.providerRepository = providerRepository;
        this.deploymentRepository = deploymentRepository;
        this.gate = gate;
        this.crypto = crypto;
        this.probe = probe;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ModelProvider> list(String tenantId) {
        return providerRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public ModelProvider get(String tenantId, Long id) {
        return require(tenantId, id);
    }

    /**
     * Creates a connection, or replaces the credential on the one named by
     * {@code id}.
     *
     * <p>A workspace may hold several connections to the same vendor, told
     * apart by name — so creating is no longer "update whatever exists for
     * this vendor". A create whose name is already taken is REJECTED rather
     * than silently overwriting: the two requests "add a second Azure
     * resource" and "rotate the key on my Azure resource" are different, and
     * guessing wrong destroys a working credential.
     *
     * <p>Saving clears the previous test outcome: the new key is unproven
     * until it is tested, and inheriting the old key's green tick would be
     * exactly the fake green light this service avoids.
     */
    @Transactional
    public ModelProvider save(String tenantId, String actor, String accessToken, Long id,
                              String kindCode, String name, String authMethod, String configJson,
                              String defaultModel, String defaultEmbeddingModel,
                              String defaultRerankModel) {
        gate.requireActive(accessToken);
        Kind kind = ModelProviderCatalog.parseKind(kindCode);
        JsonNode config = parseConfig(configJson);
        // Against ONE method's fields: an Entra ID connection has no apiKey,
        // and requiring the union would make neither combination satisfiable.
        String method = resolveAuthMethod(kind, authMethod);
        ModelProviderCatalog.validateConfig(kind, method, config);

        String label = name == null || name.isBlank()
                ? ModelProviderCatalog.spec(kind).displayName()
                : name.trim();

        ModelProvider provider;
        if (id != null) {
            provider = require(tenantId, id);
            if (provider.getKind() != kind) {
                throw CoreException.badRequest("provider_kind_mismatch",
                        "This connection is " + provider.getKind() + ", not " + kind);
            }
        } else {
            provider = new ModelProvider();
            provider.setTenantId(tenantId);
            provider.setKind(kind);
            provider.setCreatedBy(actor);
        }
        requireNameIsFree(tenantId, kind, label, provider.getId());

        provider.setName(label);
        provider.setAuthMethod(method);
        provider.setConfigEnc(crypto.encrypt(config.toString()));
        provider.setDefaultModel(blankToNull(defaultModel));
        provider.setDefaultEmbeddingModel(blankToNull(defaultEmbeddingModel));
        provider.setDefaultRerankModel(blankToNull(defaultRerankModel));
        provider.setUpdatedAt(Instant.now());
        // Unproven until tested — see javadoc.
        provider.setLastTestOk(null);
        provider.setLastTestAt(null);
        provider.setLastTestNote(null);
        provider.setModelsJson(null);
        provider.setModelsRefreshedAt(null);

        ModelProvider saved = providerRepository.save(provider);
        log.info("Tenant {} saved model provider {} \"{}\" ({})", tenantId, kind, label,
                saved.getId());
        return saved;
    }

    /**
     * Which model each purpose should default to. Kept separate from
     * {@link #save} because changing it must not require re-pasting the
     * credential — the whole point of a write-only secret is that the console
     * cannot send back what it never received.
     */
    @Transactional
    public ModelProvider setDefaults(String tenantId, String accessToken, Long id,
                                     String defaultModel, String defaultEmbeddingModel,
                                     String defaultRerankModel) {
        gate.requireActive(accessToken);
        ModelProvider provider = require(tenantId, id);
        provider.setDefaultModel(blankToNull(defaultModel));
        provider.setDefaultEmbeddingModel(blankToNull(defaultEmbeddingModel));
        provider.setDefaultRerankModel(blankToNull(defaultRerankModel));
        provider.setUpdatedAt(Instant.now());
        return providerRepository.save(provider);
    }

    private String resolveAuthMethod(Kind kind, String requested) {
        // Throws with the vendor's name when the code is not one of its own.
        return ModelProviderCatalog.spec(kind).authMethod(requested).code();
    }

    private void requireNameIsFree(String tenantId, Kind kind, String name, Long selfId) {
        providerRepository.findByTenantIdAndKindAndName(tenantId, kind, name)
                .filter(other -> !other.getId().equals(selfId))
                .ifPresent(other -> {
                    throw CoreException.badRequest("provider_name_taken",
                            "This workspace already has a " + ModelProviderCatalog.spec(kind)
                                    .displayName() + " connection called \"" + name
                                    + "\". Give this one a different name.");
                });
    }

    @Transactional
    public void delete(String tenantId, String accessToken, Long id) {
        gate.requireActive(accessToken);
        ModelProvider provider = require(tenantId, id);
        // Explicit rather than left to the schema's ON DELETE CASCADE: a model
        // declared against a credential that no longer exists is not callable
        // by anything, and the tests run on H2 where that constraint is absent.
        deploymentRepository.deleteByProviderId(id);
        providerRepository.delete(provider);
        log.info("Tenant {} removed model provider {}", tenantId, id);
    }

    @Transactional
    public ModelProvider setEnabled(String tenantId, String accessToken, Long id, boolean enabled) {
        gate.requireActive(accessToken);
        ModelProvider provider = require(tenantId, id);
        provider.setEnabled(enabled);
        provider.setUpdatedAt(Instant.now());
        return providerRepository.save(provider);
    }

    /**
     * Preflight: ask the vendor about a credential that has NOT been stored,
     * and store nothing regardless of the answer.
     *
     * <p>The twin of the cloud-connection preflight, and for the same reason.
     * Saving first and testing after leaves a rejected key sitting in the
     * table as a connection that looks configured, and the operator has to
     * clean up after a typo. Here the row is only ever written once the vendor
     * has accepted the credential.
     *
     * <p>The config is validated for the chosen auth method first, so a
     * missing field is reported as a missing field rather than as the vendor
     * turning the request down.
     */
    public ModelProviderProbe.ProbeResult verify(String accessToken, String kindCode,
                                                 String authMethod, String configJson,
                                                 String defaultModel) {
        gate.requireActive(accessToken);
        Kind kind = ModelProviderCatalog.parseKind(kindCode);
        JsonNode config = parseConfig(configJson);
        String method = resolveAuthMethod(kind, authMethod);
        ModelProviderCatalog.validateConfig(kind, method, config);
        return probe.probe(kind, config, blankToNull(defaultModel));
    }

    /**
     * A REAL call against the vendor; the outcome is stored on the row and the
     * reported model list is cached so the agent builder can offer it without
     * fanning out to every vendor on page load.
     */
    @Transactional
    public ModelProviderProbe.ProbeResult test(String tenantId, String accessToken, Long id) {
        gate.requireActive(accessToken);
        ModelProvider provider = require(tenantId, id);

        ModelProviderProbe.ProbeResult result;
        try {
            JsonNode config = objectMapper.readTree(crypto.decrypt(provider.getConfigEnc()));
            result = probe.probe(provider.getKind(), config, provider.getDefaultModel());
        } catch (CoreException ex) {
            // Key rotated without re-encrypting, or a corrupt row — surface the
            // real reason rather than reporting it as a rejected credential.
            result = new ModelProviderProbe.ProbeResult(false, ex.getMessage(), List.of());
        } catch (Exception ex) {
            result = new ModelProviderProbe.ProbeResult(false,
                    "Stored configuration could not be read", List.of());
        }

        provider.setLastTestOk(result.ok());
        provider.setLastTestAt(Instant.now());
        provider.setLastTestNote(truncate(result.message()));
        if (result.ok() && !result.models().isEmpty()) {
            provider.setModelsJson(writeModels(result.models()));
            provider.setModelsRefreshedAt(Instant.now());
        }
        provider.setUpdatedAt(Instant.now());
        providerRepository.save(provider);

        log.info("Tenant {} tested model provider {} ({}) -> {}", tenantId, id,
                provider.getKind(), result.ok());
        return result;
    }

    /**
     * Re-reads one connection's model list from the vendor.
     *
     * <p>Mechanically the same call as {@link #test} — the list endpoint is
     * the credential check — so this is deliberately a thin alias rather than
     * a second code path that could drift. It exists because "refresh my
     * models" and "check my key still works" are different intents, and a
     * scheduled sweep firing the second one would be lying about who asked.
     */
    @Transactional
    public ModelProviderProbe.ProbeResult refresh(String tenantId, String accessToken, Long id) {
        return test(tenantId, accessToken, id);
    }

    /**
     * Every connection in the workspace, refreshed in one pass.
     *
     * <p>Failures are absorbed: one unreachable vendor must not stop the other
     * ten from being refreshed, and each one's outcome is already recorded on
     * its own row for the console to read.
     */
    @Transactional
    public int refreshAll(String tenantId, String accessToken) {
        int refreshed = 0;
        for (ModelProvider provider : providerRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId)) {
            if (!provider.isEnabled()) {
                continue;
            }
            try {
                if (refresh(tenantId, accessToken, provider.getId()).ok()) {
                    refreshed++;
                }
            } catch (Exception ex) {
                log.info("Refresh of model provider {} failed: {}", provider.getId(),
                        ex.toString());
            }
        }
        return refreshed;
    }

    // ---- declared models -------------------------------------------------

    @Transactional(readOnly = true)
    public List<ModelDeployment> deployments(String tenantId, Long providerId) {
        require(tenantId, providerId);
        return deploymentRepository.findByProviderIdOrderByModelName(providerId);
    }

    /**
     * Declares a model against a connection, or updates the declaration if
     * that model name is already declared there.
     *
     * <p>Unlike a credential, re-declaring is safe to treat as an update:
     * there is nothing secret to destroy, and the model name is the identity.
     */
    @Transactional
    public ModelDeployment saveDeployment(String tenantId, String actor, String accessToken,
                                          Long providerId, Long id, String modelName,
                                          String baseModel, String purposeCode,
                                          String apiVersion, String endpoint) {
        gate.requireActive(accessToken);
        ModelProvider provider = require(tenantId, providerId);
        String name = blankToNull(modelName);
        if (name == null) {
            throw CoreException.badRequest("invalid_deployment", "A model name is required");
        }

        ModelDeployment deployment;
        if (id != null) {
            deployment = deploymentRepository.findByIdAndTenantId(id, tenantId)
                    .orElseThrow(() -> CoreException.notFound("deployment_not_found",
                            "No such model"));
        } else {
            deployment = deploymentRepository
                    .findByProviderIdAndModelName(providerId, name)
                    .orElseGet(() -> {
                        ModelDeployment fresh = new ModelDeployment();
                        fresh.setTenantId(tenantId);
                        fresh.setProviderId(providerId);
                        fresh.setCreatedBy(actor);
                        return fresh;
                    });
        }

        deployment.setModelName(name);
        deployment.setBaseModel(blankToNull(baseModel));
        deployment.setPurpose(parsePurpose(purposeCode));
        deployment.setApiVersion(blankToNull(apiVersion));
        deployment.setEndpoint(blankToNull(endpoint));
        deployment.setUpdatedAt(Instant.now());

        ModelDeployment saved = deploymentRepository.save(deployment);
        log.info("Tenant {} declared model \"{}\" on provider {} ({})", tenantId, name,
                providerId, provider.getKind());
        return saved;
    }

    @Transactional
    public void deleteDeployment(String tenantId, String accessToken, Long id) {
        gate.requireActive(accessToken);
        deploymentRepository.delete(deploymentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> CoreException.notFound("deployment_not_found",
                        "No such model")));
    }

    private static ModelPurpose parsePurpose(String code) {
        if (code == null || code.isBlank()) {
            return ModelPurpose.CHAT;
        }
        try {
            return ModelPurpose.valueOf(code.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw CoreException.badRequest("invalid_purpose",
                    "A model's purpose must be one of "
                            + java.util.Arrays.toString(ModelPurpose.values()));
        }
    }

    /** One vendor's usable models: the cached live list, else the catalog's. */
    @Transactional(readOnly = true)
    public List<String> models(String tenantId, Long id) {
        return modelsOf(require(tenantId, id));
    }

    /**
     * @param verified        the list below is the VENDOR'S OWN, cached from a
     *                        successful test — not the catalog's suggestions.
     *                        Not the same as "the key works": a Hugging Face
     *                        token or a scope-limited OpenAI key can be
     *                        accepted while listing stays unavailable, and
     *                        calling those suggestions verified would be the
     *                        fake green light this service exists to avoid.
     * @param models          every model the vendor offers, in its own order
     * @param modelsByPurpose the same ids split into CHAT / EMBEDDING / ...,
     *                        so a picker can offer only what it can use. Kept
     *                        ALONGSIDE {@code models} rather than replacing
     *                        it: callers that want the whole list still get it
     *                        unchanged.
     */
    public record AvailableModels(Kind kind, String providerName, Long providerId,
                                  boolean verified, String defaultModel,
                                  String defaultEmbeddingModel, String defaultRerankModel,
                                  List<String> models,
                                  Map<String, List<String>> modelsByPurpose,
                                  List<String> declaredModels, Instant modelsRefreshedAt) {
    }

    /**
     * What the agent builder offers. Enabled providers only, newest first.
     *
     * <p>Unverified providers are INCLUDED, flagged {@code verified=false}:
     * a tenant that has just pasted a key should still see its models, and the
     * console can mark them as unproven rather than hiding them with no
     * explanation.
     */
    @Transactional(readOnly = true)
    public List<AvailableModels> availableModels(String tenantId) {
        // One query for every declared model in the workspace rather than one
        // per connection: this runs on each page load of the agent builder.
        Map<Long, List<ModelDeployment>> declaredByProvider = new LinkedHashMap<>();
        for (ModelDeployment deployment : deploymentRepository.findByTenantId(tenantId)) {
            if (deployment.isEnabled()) {
                declaredByProvider
                        .computeIfAbsent(deployment.getProviderId(), k -> new ArrayList<>())
                        .add(deployment);
            }
        }

        List<AvailableModels> out = new ArrayList<>();
        for (ModelProvider provider : providerRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId)) {
            if (!provider.isEnabled()) {
                continue;
            }
            List<ModelDeployment> declared =
                    declaredByProvider.getOrDefault(provider.getId(), List.of());
            List<String> probed = modelsOf(provider);

            // Declared first: on the vendors that have any, they are the ones
            // the tenant actually deployed, and a probed id they did not
            // create should not outrank them in a picker.
            List<String> models = new ArrayList<>();
            for (ModelDeployment deployment : declared) {
                models.add(deployment.getModelName());
            }
            for (String id : probed) {
                if (!models.contains(id)) {
                    models.add(id);
                }
            }

            boolean listedByVendor = provider.getModelsJson() != null
                    && !provider.getModelsJson().isBlank();
            out.add(new AvailableModels(provider.getKind(), provider.getName(), provider.getId(),
                    listedByVendor || !declared.isEmpty(), provider.getDefaultModel(),
                    provider.getDefaultEmbeddingModel(), provider.getDefaultRerankModel(),
                    List.copyOf(models), purposesOf(provider.getKind(), declared, probed),
                    declared.stream().map(ModelDeployment::getModelName).toList(),
                    provider.getModelsRefreshedAt()));
        }
        return List.copyOf(out);
    }

    /**
     * Declared models carry the purpose their operator CHOSE; probed ids fall
     * back to the classifier. That split is the point of declaring one: no
     * naming rule can tell that an Azure deployment called "prod-embed-v2" is
     * an embedding model, but the person who created it can.
     */
    private Map<String, List<String>> purposesOf(Kind kind, List<ModelDeployment> declared,
                                                 List<String> probed) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (ModelDeployment deployment : declared) {
            grouped.computeIfAbsent(deployment.getPurpose().name(), k -> new ArrayList<>())
                    .add(deployment.getModelName());
        }
        Set<String> alreadyDeclared = declared.stream()
                .map(ModelDeployment::getModelName)
                .collect(java.util.stream.Collectors.toSet());
        ModelPurposeClassifier.groupByPurpose(kind, probed).forEach((purpose, ids) -> {
            for (String id : ids) {
                if (!alreadyDeclared.contains(id)) {
                    grouped.computeIfAbsent(purpose, k -> new ArrayList<>()).add(id);
                }
            }
        });
        grouped.replaceAll((purpose, ids) -> List.copyOf(ids));
        return Map.copyOf(grouped);
    }

    /**
     * A tenant's decrypted credential for the model an agent is about to run.
     *
     * <p>This is the only method in the service that hands plaintext secrets
     * to another service, and it exists because core-service is the ONLY
     * holder of the encryption key. agent-service can build a client for
     * eleven vendors and cannot decrypt a single stored credential; the split
     * is deliberate, so the crossing is narrow, internal-token guarded, and
     * per-call.
     *
     * <p>Resolution order, and the reason for it:
     * <ol>
     *   <li>a declared deployment whose model name matches — the tenant said
     *       explicitly "this name lives on that connection", which beats any
     *       guess;</li>
     *   <li>a connection whose probed/catalog model list contains the name;</li>
     *   <li>a connection whose {@code defaultModel} is the name.</li>
     * </ol>
     * Disabled connections are skipped throughout: a tenant that switched a
     * provider off means it, and quietly billing them on it would be the kind
     * of surprise this codebase avoids.
     *
     * <p>No match is an error, never a fallback to "some other provider that
     * might work". Running {@code gpt-4o} on whatever connection happened to
     * be first would send a tenant's data to a vendor they did not pick.
     */
    @Transactional(readOnly = true)
    public ResolvedCredentials resolveForModel(String tenantId, String model) {
        String wanted = model == null ? "" : model.trim();
        if (wanted.isEmpty()) {
            throw CoreException.badRequest("model_required",
                    "The agent has no model set, so there is no credential to resolve.");
        }

        List<ModelProvider> enabled = providerRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(ModelProvider::isEnabled)
                .toList();
        if (enabled.isEmpty()) {
            throw CoreException.badRequest("no_model_provider",
                    "This workspace has no enabled AI connection. Add one under "
                            + "Settings > AI Providers before running an agent.");
        }

        for (ModelDeployment deployment : deploymentRepository.findByTenantId(tenantId)) {
            if (deployment.isEnabled() && wanted.equalsIgnoreCase(deployment.getModelName())) {
                for (ModelProvider provider : enabled) {
                    if (provider.getId().equals(deployment.getProviderId())) {
                        return decrypted(provider, wanted);
                    }
                }
            }
        }
        for (ModelProvider provider : enabled) {
            if (modelsOf(provider).stream().anyMatch(wanted::equalsIgnoreCase)) {
                return decrypted(provider, wanted);
            }
        }
        for (ModelProvider provider : enabled) {
            if (wanted.equalsIgnoreCase(provider.getDefaultModel())) {
                return decrypted(provider, wanted);
            }
        }

        throw CoreException.badRequest("model_not_available",
                "No enabled AI connection in this workspace offers \"" + wanted
                        + "\". Check the model id on the agent, or add the connection "
                        + "that serves it.");
    }

    /**
     * THIS WORKSPACE'S default chat model.
     *
     * <p><b>Why this has to exist.</b> Credentials were always multi-tenant —
     * {@link #resolveForModel} takes a tenantId and decrypts that workspace's
     * own key. WHICH MODEL TO ASK FOR was not. A workflow either named a
     * literal id, which only resolves on a workspace that happens to hold a
     * connection offering it, or fell back to {@code RUNTIME_DEFAULT_MODEL} —
     * one environment variable for the entire deployment, the same string for
     * every customer.
     *
     * <p>That is fine for a workflow a customer wrote for themselves and wrong
     * for the ones this platform is built around. A provider designs a workflow
     * once and rolls it out to fifty workspaces; if it names
     * {@code deepseek.v3.2} it runs only where someone happens to have Bedrock,
     * and if it names nothing every workspace is silently pushed onto the
     * operator's model — billed to the operator's account, with the customer's
     * data going to a vendor they did not choose.
     *
     * <p>The default is the {@code defaultModel} of an enabled connection.
     * <b>Two connections both declaring one is an error, not a coin toss</b> —
     * the same rule {@link #resolveForModel} already follows, and for the same
     * reason: quietly picking the first would send a workspace's data to a
     * vendor nobody selected.
     */
    @Transactional(readOnly = true)
    public String defaultChatModel(String tenantId) {
        List<ModelProvider> candidates = providerRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(ModelProvider::isEnabled)
                .filter(provider -> provider.getDefaultModel() != null
                        && !provider.getDefaultModel().isBlank())
                .toList();

        if (candidates.isEmpty()) {
            throw CoreException.badRequest("no_default_model",
                    "This workspace has not chosen a default AI model, so an automation "
                            + "that does not name one cannot run. Open Settings > AI "
                            + "Providers, pick a connection and set its default model.");
        }
        if (candidates.size() > 1) {
            throw CoreException.badRequest("ambiguous_default_model",
                    "This workspace has more than one connection claiming a default model ("
                            + candidates.stream().map(ModelProvider::getName)
                                    .collect(Collectors.joining(", "))
                            + "). Clear the default on all but one, or name a model on the "
                            + "step itself.");
        }
        return candidates.getFirst().getDefaultModel();
    }

    /**
     * @param kind   the vendor, as agent-service's {@code ModelVendor}
     *               spells it — the two enums share names by contract
     * @param values the decrypted config, keyed as the catalog's form fields
     *               name them ({@code apiKey}, {@code region}, ...)
     */
    public record ResolvedCredentials(Kind kind, Long providerId, String providerName,
                                      String model, Map<String, String> values) {
    }

    private ResolvedCredentials decrypted(ModelProvider provider, String model) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            JsonNode config = objectMapper.readTree(crypto.decrypt(provider.getConfigEnc()));
            config.fields().forEachRemaining(entry -> {
                if (entry.getValue() != null && !entry.getValue().isNull()) {
                    values.put(entry.getKey(), entry.getValue().asText());
                }
            });
        } catch (CoreException ex) {
            throw ex;
        } catch (Exception ex) {
            // Key rotated without re-encrypting, or a corrupt row. Saying so
            // beats handing back an empty map that fails later as a 401 and
            // sends the tenant looking at their vendor account.
            throw CoreException.badRequest("model_credentials_unreadable",
                    "The stored credential for \"" + provider.getName()
                            + "\" could not be decrypted. Re-enter the key under "
                            + "Settings > AI Providers.");
        }
        return new ResolvedCredentials(provider.getKind(), provider.getId(), provider.getName(),
                model, values);
    }

    private List<String> modelsOf(ModelProvider provider) {
        if (provider.getModelsJson() != null && !provider.getModelsJson().isBlank()) {
            try {
                return List.copyOf(objectMapper.readValue(provider.getModelsJson(),
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, String.class)));
            } catch (Exception ex) {
                log.warn("Model cache for provider {} is unreadable; using catalog fallback",
                        provider.getId());
            }
        }
        return ModelProviderCatalog.spec(provider.getKind()).fallbackModels();
    }

    private String writeModels(List<String> models) {
        try {
            return objectMapper.writeValueAsString(models);
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw CoreException.badRequest("invalid_provider_config",
                    "Provider configuration is required");
        }
        try {
            JsonNode config = objectMapper.readTree(configJson);
            if (!config.isObject()) {
                throw CoreException.badRequest("invalid_provider_config",
                        "Provider configuration must be a JSON object");
            }
            return config;
        } catch (CoreException ex) {
            throw ex;
        } catch (Exception ex) {
            throw CoreException.badRequest("invalid_provider_config",
                    "Provider configuration is not valid JSON");
        }
    }

    private ModelProvider require(String tenantId, Long id) {
        return providerRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> CoreException.notFound("model_provider_not_found",
                        "No such model provider"));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 509) + "...";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
