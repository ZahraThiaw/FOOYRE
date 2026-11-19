/**
 * Écran bloquant affiché lorsque le mode démonstration a été demandé
 * (VITE_ENABLE_MOCKS=true) mais n'a pas pu être initialisé (F2).
 *
 * Volontairement indépendant de React et de l'application : celle‑ci n'est PAS chargée dans
 * ce cas. Les textes viennent du catalogue i18n ; le contenu est inséré avec `textContent`
 * (aucune interprétation HTML du message d'erreur).
 */
export async function renderMockStartupFailure(
  container: HTMLElement,
  error: unknown,
): Promise<void> {
  // Développement uniquement (les données fictives n'existent pas en production).
  console.error("[FOOYRE] Mode démonstration non initialisé : l'application est bloquée.", error);

  const { default: i18n } = await import('@/shared/i18n');
  const technicalDetail = error instanceof Error ? error.message : String(error);

  const panel = document.createElement('main');
  panel.setAttribute('role', 'alert');
  panel.className = 'mx-auto max-w-xl space-y-4 px-4 py-10';

  const title = document.createElement('h1');
  title.className = 'text-2xl font-bold';
  title.textContent = i18n.t('errors.mockStartup.title');

  const description = document.createElement('p');
  description.textContent = i18n.t('errors.mockStartup.description');

  const action = document.createElement('p');
  action.className = 'text-ink-muted';
  action.textContent = i18n.t('errors.mockStartup.action');

  const detail = document.createElement('pre');
  detail.className = 'whitespace-pre-wrap break-words rounded bg-warning-surface p-3 text-sm';
  detail.textContent = i18n.t('errors.mockStartup.detail', { detail: technicalDetail });

  panel.append(title, description, action, detail);
  container.replaceChildren(panel);
}
