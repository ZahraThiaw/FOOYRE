/**
 * Clé d'idempotence (UUID v4) générée AU MOMENT DE LA SAISIE et réutilisée à chaque nouvel envoi de
 * la même opération (docs/api-specification.md §1.4) : un double clic ou une nouvelle tentative
 * après une coupure réseau ne crée jamais deux mouvements. `crypto.getRandomValues` est disponible
 * sur toute la cible navigateurs (contrairement à `crypto.randomUUID`, Safari ≥ 15.4).
 */
export function newIdempotencyKey(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
