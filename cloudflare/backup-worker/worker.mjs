const retryDelays = [10_000, 30_000, 120_000];
const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

export async function runScheduledBackup(env, send = fetch, pause = wait) {
  let endpoint;
  try {
    endpoint = new URL(env.BACKUP_TRIGGER_URL);
  } catch {
    throw new Error("Backup Worker URL is missing or invalid");
  }
  if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password || !env.BACKUP_TRIGGER_TOKEN) {
    throw new Error("Backup Worker configuration is invalid");
  }

  for (let attempt = 0; attempt <= retryDelays.length; attempt++) {
    let response;
    try {
      response = await send(endpoint, {
        method: "POST",
        headers: { authorization: `Bearer ${env.BACKUP_TRIGGER_TOKEN}` },
        redirect: "error",
        signal: AbortSignal.timeout(120_000),
      });
    } catch {
      // A cold start or network timeout can leave the outcome unknown.
    }

    if (response?.ok) {
      const result = await response.json().catch(() => null);
      if (result?.outcome === "uploaded" || result?.outcome === "already_exists") return result.outcome;
    } else if (response && ![408, 409, 425, 429].includes(response.status) && response.status < 500) {
      await response.body?.cancel();
      throw new Error(`Backup trigger rejected the request with HTTP ${response.status}`);
    } else {
      await response?.body?.cancel();
    }
    if (attempt < retryDelays.length) await pause(retryDelays[attempt]);
  }
  throw new Error("Backup trigger did not confirm an upload after four attempts");
}

export default {
  async scheduled(controller, env) {
    const outcome = await runScheduledBackup(env);
    console.info("Daily backup", { outcome, scheduledTime: controller.scheduledTime });
  },
};
