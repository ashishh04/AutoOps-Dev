/**
 * Models — this workspace's AI vendor credentials.
 *
 * There is ONE model surface, and this is it: the tenant's own
 * bring-your-own-key credentials, stored encrypted by core-service and never
 * echoed back to the browser.
 *
 * A second tab used to sit here driving a shared third-party workspace — its
 * providers, plugin marketplace and system defaults. It went when the engine
 * behind it did. Its token could read and delete every app in that workspace,
 * which is precisely why a browser should never have been able to reach it.
 */

import React from "react";
import { PageHeader } from "../../components/app/appui";
import AiProviders from "./AiProviders";

export default function Models() {
  return (
    <div className="animate-fade-up">
      <PageHeader
        title="Models"
        subtitle="Connect the AI vendors your team pays for, and choose what your agents run on."
      />
      <AiProviders embedded />
    </div>
  );
}
