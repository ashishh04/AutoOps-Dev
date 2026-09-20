import io
p = "src/test/java/com/intertec/autoops/agent/service/FindingIngestServiceTest.java"
s = io.open(p, encoding="utf-8").read()

s = s.replace("@Import(FindingIngestService.class)",
              "@Import({FindingIngestService.class, VerdictAttributionService.class})")
s = s.replace("import com.intertec.autoops.agent.repo.FindingObservationRepository;",
              "import com.intertec.autoops.agent.domain.Agent;\n"
              "import com.intertec.autoops.agent.domain.AgentRun;\n"
              "import com.intertec.autoops.agent.repo.AgentRepository;\n"
              "import com.intertec.autoops.agent.repo.AgentRunRepository;\n"
              "import com.intertec.autoops.agent.repo.FindingObservationRepository;")
s = s.replace("import org.springframework.context.annotation.Import;",
              "import org.springframework.context.annotation.Import;\n"
              "import org.springframework.jdbc.core.JdbcTemplate;")
s = s.replace('    private static final Long PROJECT = 9L;',
              '    private static final Long PROJECT = 9L;\n'
              '    private static final String AGENT_NAME = "aws.idle_resource_reclaimer";\n'
              '\n'
              '    private Long openRunId;')
s = s.replace("    @Autowired\n    private FindingRepository findings;",
              "    @Autowired\n    private FindingRepository findings;\n"
              "    @Autowired\n    private AgentRepository agents;\n"
              "    @Autowired\n    private AgentRunRepository agentRuns;\n"
              "    @Autowired\n    private JdbcTemplate jdbc;")
s = s.replace("        return service.ingest(TENANT, PROJECT, 77L, verdict);",
              "        return service.ingest(TENANT, PROJECT, openRunId, verdict);")

old = "    void reset() {\n        verdictCounter = 0;\n    }"
new = "\n".join([
 "    void reset() {",
 "        verdictCounter = 0;",
 "",
 "        // @DataJpaTest builds its schema from the JPA entities, so a table that",
 "        // only a Flyway migration creates does not exist here. This one is a",
 "        // counter with a composite key and an upsert, which is worse as an entity",
 "        // than as a few lines of SQL — so it is created by hand, and the real DDL",
 "        // is asserted against a real MySQL in SchemaInvariantsIT.",
 '        jdbc.execute("CREATE TABLE IF NOT EXISTS verdict_attribution_daily ('',
 '                + "tenant_id VARCHAR(64) NOT NULL, agent_name VARCHAR(128) NOT NULL, "',
 '                + "day DATE NOT NULL, attributed BIGINT NOT NULL DEFAULT 0, "',
 '                + "unattributed BIGINT NOT NULL DEFAULT 0, "',
 '                + "foreign_run BIGINT NOT NULL DEFAULT 0, late BIGINT NOT NULL DEFAULT 0, "',
 '                + "PRIMARY KEY (tenant_id, agent_name, day))");',
 '        jdbc.update("DELETE FROM verdict_attribution_daily");',
 "",
 "        // A real agent and a real open run, because ingest now validates that a",
 "        // verdict's run belongs to the agent that emitted it. Passing a run id",
 "        // nobody owns would make every test below exercise the refusal path",
 "        // rather than the disposition table it is about.",
 "        Agent agent = new Agent();",
 "        agent.setTenantId(TENANT);",
 "        agent.setProjectId(PROJECT);",
 "        agent.setName(AGENT_NAME);",
 "        agent = agents.save(agent);",
 "",
 "        AgentRun run = new AgentRun();",
 "        run.setTenantId(TENANT);",
 "        run.setProjectId(PROJECT);",
 "        run.setAgentId(agent.getId());",
 '        run.setInput("{}");',
 "        run.setStatus(AgentRun.Status.RUNNING);",
 "        openRunId = agentRuns.save(run).getId();",
 "    }"])
assert old in s, "reset() anchor missing"
s = s.replace(old, new)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("patched")
