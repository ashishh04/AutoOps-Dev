-- Why a delivered workflow now records WHAT IT WAS DELIVERED AS.
--
-- Workflows and agents are sold separately. An agent, though, can only act
-- through automations that live in the customer's own project and run with
-- their credentials under their approval gates — so rolling out an agent has
-- always had to deliver the workflows it names.
--
-- Until now those arrived indistinguishable from a workflow the customer had
-- actually been given: visible in their list, runnable on their own. That is a
-- commercial hole (they get a product nobody sold them) and a trust one — the
-- honest question "I did not ask for this, how is it in my environment?" had
-- no good answer.
--
--   PRODUCT          delivered in its own right. Visible, runnable.
--   AGENT_COMPONENT  delivered only because an agent needs it. Hidden from the
--                    tenant's list and refused by the tenant run path. The
--                    agent still reaches it, over /internal, where tool
--                    resolution reads every row regardless of delivery.
--
-- Everything already in the table predates agent rollout as a separate concept
-- and was delivered as a product, so the DEFAULT is correct for every existing
-- row and no backfill is needed.
ALTER TABLE workflows
  ADD COLUMN delivery ENUM('PRODUCT','AGENT_COMPONENT') NOT NULL DEFAULT 'PRODUCT'
  AFTER origin;

-- Both uniqueness rules have to admit the delivery kind, because a customer who
-- later BUYS a workflow that is already present as a hidden component gets a
-- second, independent copy they own — and the old indexes refused it.
--
-- (project_id, source_id, delivery): two copies of the same catalog item in one
-- project are still a defect, unless they are a sealed component and a licensed
-- product, which are different things with different lifecycles.
DROP INDEX uq_workflows_project_source ON workflows;
CREATE UNIQUE INDEX uq_workflows_project_source_delivery
  ON workflows (project_id, source_id, delivery);

-- (project_id, name, delivery): the component and the product copy carry the
-- same name. That is not ambiguous in the customer's list, because only one of
-- the two is ever shown there.
DROP INDEX uq_workflows_project_name ON workflows;
CREATE UNIQUE INDEX uq_workflows_project_name_delivery
  ON workflows (project_id, name, delivery);
