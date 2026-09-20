-- MySQL dump 10.13  Distrib 8.4.11, for Linux (x86_64)
--
-- Host: localhost    Database: autoops_core
-- ------------------------------------------------------
-- Server version	8.4.11

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Dumping data for table `library_items`
--
-- WHERE:  definition LIKE "%difyWorkflow%"

LOCK TABLES `library_items` WRITE;
/*!40000 ALTER TABLE `library_items` DISABLE KEYS */;
INSERT INTO `library_items` (`id`, `tenant_id`, `title`, `description`, `type`, `category`, `premium`, `definition`, `installs`, `source_id`, `created_by`, `created_at`) VALUES (247,NULL,'Agentic Business Research and Report Generator','Runs multi-stage business research â€” market, technical, competitor, trend â€” then fact-checks the evidence and writes a report. Returns the report as markdown, PDF-ready markdown and Word-ready HTML.','WORKFLOW','Research',0,'{\"difyWorkflow\":\"business-research\"}',0,NULL,'setup','2026-08-19 11:24:02.724437'),(248,NULL,'Incident Postmortem Writer','Turns a raw incident timeline into a structured post-incident review â€” impact, timeline, root cause and follow-up actions. States plainly when the root cause cannot be established rather than inventing one.','WORKFLOW','Operations',0,'{\"difyWorkflow\":\"incident-postmortem\"}',0,NULL,'setup','2026-08-19 15:41:26.213348'),(249,NULL,'Meeting Actions and Follow-up','Turns raw meeting notes into an owner-and-action table plus a ready-to-send follow-up email. Every action carries an owner, or is marked unassigned rather than guessed.','WORKFLOW','Productivity',0,'{\"difyWorkflow\":\"meeting-actions\"}',0,NULL,'setup','2026-08-19 15:41:40.940905');
/*!40000 ALTER TABLE `library_items` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-19  4:27:38
