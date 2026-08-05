import { createPublicKey, verify as cryptoVerify } from "node:crypto";
import { buildChallengeSigningPayload } from "@slash-api-mock/contracts";

/**
 * Agent가 base64(raw 32byte) 형식으로 보낸 Ed25519 공개키를 JWK로 감싸 검증한다.
 * Node의 crypto 모듈은 OKP/Ed25519 JWK Import를 표준 지원하므로 외부 서명 라이브러리가 필요 없다.
 */
function publicKeyFromBase64Raw(publicKeyBase64: string) {
  const raw = Buffer.from(publicKeyBase64, "base64");
  const jwk = {
    kty: "OKP",
    crv: "Ed25519",
    x: raw.toString("base64url"),
  };
  return createPublicKey({ key: jwk, format: "jwk" });
}

export function verifyEd25519Signature(params: {
  payload: string;
  signatureBase64: string;
  publicKeyBase64: string;
}): boolean {
  try {
    const message = Buffer.from(params.payload);
    const signature = Buffer.from(params.signatureBase64, "base64");
    const publicKey = publicKeyFromBase64Raw(params.publicKeyBase64);
    return cryptoVerify(null, message, publicKey, signature);
  } catch {
    return false;
  }
}

export function verifyChallengeSignature(params: {
  challengeId: string;
  nonce: string;
  deviceId: string;
  signatureBase64: string;
  publicKeyBase64: string;
}): boolean {
  return verifyEd25519Signature({
    payload: buildChallengeSigningPayload({
      challengeId: params.challengeId,
      nonce: params.nonce,
      deviceId: params.deviceId,
    }),
    signatureBase64: params.signatureBase64,
    publicKeyBase64: params.publicKeyBase64,
  });
}
