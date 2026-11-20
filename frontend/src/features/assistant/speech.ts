/**
 * Reconnaissance vocale du NAVIGATEUR (API Web Speech, préfixe `webkit` sur Chrome/Edge/Safari).
 * Aucun enregistrement audio n'est conservé ni envoyé à FOOYRE : seul le texte transcrit est
 * placé dans le champ de saisie. Les types ne font pas partie de la bibliothèque DOM de TypeScript.
 */
export interface SpeechRecognitionLike {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  maxAlternatives: number;
  onresult: ((event: { results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }> }) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}

type Constructor = new () => SpeechRecognitionLike;

export function speechRecognitionConstructor(): Constructor | null {
  const scope = globalThis as unknown as { SpeechRecognition?: Constructor; webkitSpeechRecognition?: Constructor };
  return scope.SpeechRecognition ?? scope.webkitSpeechRecognition ?? null;
}

/** Erreur du navigateur → clé de message. */
export function speechErrorKey(error: string): 'denied' | 'noSpeech' | 'network' | 'error' | null {
  switch (error) {
    case 'not-allowed':
    case 'service-not-allowed':
    case 'audio-capture':
      return 'denied';
    case 'no-speech':
      return 'noSpeech';
    case 'network':
      return 'network';
    case 'aborted':
      return null;
    default:
      return 'error';
  }
}
