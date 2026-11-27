import { AwsClient } from "aws4fetch";
import type { BackupStorage } from "./service";

interface R2Config {
  accountId: string;
  bucket: string;
  accessKeyId: string;
  secretAccessKey: string;
}

export function createR2Storage(config: R2Config, send = fetch): BackupStorage {
  const { accountId, bucket, accessKeyId, secretAccessKey } = config;
  if (!/^[a-f0-9]+$/i.test(accountId) || !/^[a-z0-9][a-z0-9-]*[a-z0-9]$/.test(bucket)) {
    throw new Error("Invalid R2 backup target");
  }
  const client = new AwsClient({ accessKeyId, secretAccessKey, service: "s3", region: "auto" });
  const endpoint = `https://${accountId}.r2.cloudflarestorage.com/${bucket}/`;
  const objectUrl = (key: string) => endpoint + key.split("/").map(encodeURIComponent).join("/");

  return {
    async exists(key) {
      const request = await client.sign(objectUrl(key), { method: "HEAD", signal: AbortSignal.timeout(30000), redirect: "error" });
      const response = await send(request);
      if (response.status === 404) return false;
      if (!response.ok) throw new Error(`R2 HEAD returned ${response.status}`);
      return true;
    },
    async put(key, bytes) {
      const request = await client.sign(objectUrl(key), {
        method: "PUT",
        headers: {
          "content-type": "application/octet-stream",
          "if-none-match": "*",
          "x-amz-storage-class": "STANDARD",
        },
        body: Buffer.from(bytes),
        aws: { allHeaders: true },
        signal: AbortSignal.timeout(60000),
        redirect: "error",
      });
      const response = await send(request);
      await response.body?.cancel();
      if (response.status === 412) return "already_exists";
      if (!response.ok) throw new Error(`R2 PUT returned ${response.status}`);
      return "uploaded";
    },
  };
}
