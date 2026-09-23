-- Runs on FIRST MySQL container init only (docker-entrypoint-initdb.d).
-- The image's env vars create autoops_auth + the autoops user; this adds the
-- other platform databases and grants.
--
-- ‼️ ADDING A DATABASE HERE DOES NOTHING TO AN ENVIRONMENT THAT ALREADY EXISTS.
-- docker-entrypoint-initdb.d only runs while the data directory is empty, so a
-- line added after the volume was created is never executed. The new service
-- then dies at boot on a Flyway error that names the cause exactly:
--
--     Access denied for user 'autoops'@'%' to database 'autoops_<name>'
--     Error Code : 1044
--
-- which reads like a credentials problem and is actually a missing database.
-- Recreating the volume would fix it and destroy the Intertec demo data, so
-- don't. Apply the same two statements to the running server instead:
--
--     docker exec autoops-mysql-1 mysql -uroot -prootpass -e "
--       CREATE DATABASE IF NOT EXISTS autoops_<name>
--           CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
--       GRANT ALL PRIVILEGES ON autoops_<name>.* TO 'autoops'@'%';
--       FLUSH PRIVILEGES;"
--
-- Keep this file in step regardless — it is what makes a FRESH checkout work.
-- (This has now caught autoops_workflow/autoops_agent once, and autoops_plugin.)
CREATE DATABASE IF NOT EXISTS autoops_subscription
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON autoops_subscription.* TO 'autoops'@'%';
CREATE DATABASE IF NOT EXISTS autoops_core
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON autoops_core.* TO 'autoops'@'%';
-- Workflow definitions and AI agents own their data, in their own schemas.
CREATE DATABASE IF NOT EXISTS autoops_workflow
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON autoops_workflow.* TO 'autoops'@'%';
CREATE DATABASE IF NOT EXISTS autoops_agent
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON autoops_agent.* TO 'autoops'@'%';
-- Notification channels (Slack, Teams, Outlook, Gmail, GitHub, webhook) and
-- the per-tenant rules that fire them.
CREATE DATABASE IF NOT EXISTS autoops_plugin
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON autoops_plugin.* TO 'autoops'@'%';
-- Connections to a tenant's OWN Rundeck server, plus the dispatch receipts for
-- jobs AutoOps told it to run. Jobs, executions and nodes are NOT mirrored here
-- — they are read live from Rundeck.
CREATE DATABASE IF NOT EXISTS autoops_rundeck
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON autoops_rundeck.* TO 'autoops'@'%';
FLUSH PRIVILEGES;

-- alert-service's first database. It stays a facade over Keep and the incident
-- engine for alerts and incidents themselves; this holds only what the platform
-- itself decided — where a page went, whether it was acked, and whether the
-- routing turned out to be right.
CREATE DATABASE IF NOT EXISTS autoops_alerts
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON autoops_alerts.* TO 'autoops'@'%';
FLUSH PRIVILEGES;
