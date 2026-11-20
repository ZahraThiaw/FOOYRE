import { useMutation, useQuery } from '@tanstack/react-query';
import { Bot, Loader2, Mic, MicOff, Send, Sparkles, Trash2 } from 'lucide-react';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';

import { buttonClassName, panelClassName, primaryButtonClassName, secondaryButtonClassName } from '@/shared/ui/styles';
import { createDraft } from '@/features/sales/api';
import { hasPermission, useMe } from '@/features/session/api';
import { httpClient } from '@/shared/api/httpClient';
import { newIdempotencyKey } from '@/shared/lib/idempotencyKey';
import { formatQuantity } from '@/shared/lib/formatQuantity';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { useNotify } from '@/shared/ui/useNotify';

import { speechErrorKey, speechRecognitionConstructor, type SpeechRecognitionLike } from './speech';

/** Historique MAÎTRISÉ : 12 derniers messages en mémoire, jamais stockés ; 6 tours transmis. */
const MAX_MESSAGES = 12;
const MAX_QUESTION = 1000;
const SPEECH_LANG = 'fr-FR';
const STOCK_SEARCH = '/stock?q=';
const SUGGESTIONS = ['lowStock', 'today', 'top', 'stock', 'customers'] as const;

interface AssistantStatus {
  readonly globallyEnabled: boolean;
  readonly configured: boolean;
  readonly businessConsent: boolean;
  readonly canManageConsent: boolean;
}

interface ProposedLine {
  readonly productId: string;
  readonly productName: string;
  readonly unit: string;
  readonly quantity: string;
}

interface Proposal {
  readonly type: 'PREPARE_SALE' | 'SEARCH_PRODUCT' | 'PREPARE_CUSTOMER';
  readonly customerId: string | null;
  readonly customerName: string | null;
  readonly lines: readonly ProposedLine[];
  readonly query: string | null;
  readonly newCustomerName: string | null;
  readonly newCustomerPhone: string | null;
}

interface Answer {
  readonly answer: string;
  readonly insufficientData: boolean;
  readonly proposal: Proposal | null;
}

interface Message {
  readonly id: number;
  readonly role: 'user' | 'assistant';
  readonly text: string;
  readonly insufficientData?: boolean;
  readonly proposal?: Proposal | null;
}

function ProposalCard({ proposal, onDone }: { readonly proposal: Proposal; readonly onDone: () => void }) {
  const { t } = useTranslation(['assistant', 'stock']);
  const navigate = useNavigate();
  const notify = useNotify();
  const [key] = useState(newIdempotencyKey);
  const create = useMutation({
    mutationFn: () =>
      createDraft(
        {
          customerId: proposal.customerId,
          lines: proposal.lines.map((line) => ({ productId: line.productId, quantity: line.quantity, discount: null })),
          globalDiscount: null,
        },
        key,
      ),
  });

  if (proposal.type === 'SEARCH_PRODUCT' && proposal.query) {
    return (
      <div className="mt-3 rounded-xl border border-accent bg-accent-soft p-3 text-sm text-ink">
        <p className="font-medium">{t('assistant:proposal.searchTitle', { query: proposal.query })}</p>
        <Link to={STOCK_SEARCH + encodeURIComponent(proposal.query)} className={`${secondaryButtonClassName} mt-2 text-sm`}>
          {t('assistant:proposal.openSearch')}
        </Link>
      </div>
    );
  }
  if (proposal.type === 'PREPARE_CUSTOMER') {
    const params = new URLSearchParams();
    if (proposal.newCustomerName) params.set('name', proposal.newCustomerName);
    if (proposal.newCustomerPhone) params.set('phone', proposal.newCustomerPhone);
    return (
      <div className="mt-3 rounded-xl border border-accent bg-accent-soft p-3 text-sm text-ink">
        <p className="font-medium">{t('assistant:proposal.customerTitle')}</p>
        <p>{[proposal.newCustomerName, proposal.newCustomerPhone].filter(Boolean).join(' · ')}</p>
        <Link to={`/clients/new?${params.toString()}`} className={`${secondaryButtonClassName} mt-2 text-sm`}>
          {t('assistant:proposal.openCustomer')}
        </Link>
      </div>
    );
  }
  return (
    <div className="mt-3 rounded-xl border border-accent bg-accent-soft p-3 text-sm text-ink">
      <p className="font-semibold">{t('assistant:proposal.saleTitle')}</p>
      <p>{proposal.customerName ? t('assistant:proposal.customer', { name: proposal.customerName }) : t('assistant:proposal.noCustomer')}</p>
      <ul className="my-2 list-disc pl-5">
        {proposal.lines.map((line) => (
          <li key={line.productId}>
            {formatQuantity(line.quantity)} {t(`stock:unitShort.${line.unit as 'PIECE'}`)} · {line.productName}
          </li>
        ))}
      </ul>
      <p className="text-xs text-ink-muted">{t('assistant:proposal.saleHint')}</p>
      {create.error && <ApiErrorMessage error={create.error} />}
      <div className="mt-2 flex flex-wrap">
        <button
          type="button"
          disabled={create.isPending}
          onClick={() =>
            create.mutate(undefined, {
              onSuccess: (sale) => {
                notify('success', t('assistant:proposal.created'));
                void navigate(`/sales/${sale.id}`);
              },
              onError: () => notify('error', t('assistant:proposal.failed')),
            })
          }
          className={`${primaryButtonClassName} mb-2 mr-2 text-sm`}
        >
          {create.isPending ? t('assistant:proposal.creating') : t('assistant:proposal.createDraft')}
        </button>
        <button type="button" onClick={onDone} className={`${secondaryButtonClassName} mb-2 text-sm`}>
          {t('assistant:proposal.dismiss')}
        </button>
      </div>
    </div>
  );
}

export function AssistantPage() {
  const { t } = useTranslation(['assistant']);
  const { data: me } = useMe();
  const inputId = useId();
  const [question, setQuestion] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [listening, setListening] = useState(false);
  const [voiceMessage, setVoiceMessage] = useState<string | null>(null);
  const recognition = useRef<SpeechRecognitionLike | null>(null);
  const nextId = useRef(1);
  const endRef = useRef<HTMLDivElement>(null);
  const speechSupported = speechRecognitionConstructor() !== null;

  const status = useQuery({
    queryKey: ['assistant', 'status'],
    queryFn: async () => (await httpClient.get<AssistantStatus>('/assistant/status')).data,
  });
  const ask = useMutation({
    mutationFn: async (text: string) => {
      const history = messages.slice(-MAX_MESSAGES).map((message) => ({ role: message.role, text: message.text }));
      return (await httpClient.post<Answer>('/assistant/messages', { question: text, history })).data;
    },
  });

  useEffect(() => () => recognition.current?.abort(), []);
  useEffect(() => {
    // Accolades OBLIGATOIRES : scrollIntoView renvoie une promesse dans Chromium récent (ne pas la renvoyer à React).
    void endRef.current?.scrollIntoView?.({ block: 'end' });
  }, [messages, ask.isPending]);

  const ready = status.data?.globallyEnabled && status.data.configured && status.data.businessConsent;

  function push(message: Omit<Message, 'id'>) {
    setMessages((current) => [...current, { ...message, id: nextId.current++ }].slice(-MAX_MESSAGES));
  }

  function send(text: string) {
    const trimmed = text.trim().slice(0, MAX_QUESTION);
    if (trimmed === '' || ask.isPending) return;
    push({ role: 'user', text: trimmed });
    setQuestion('');
    ask.mutate(trimmed, {
      onSuccess: (answer) =>
        push({ role: 'assistant', text: answer.answer, insufficientData: answer.insufficientData, proposal: answer.proposal }),
    });
  }

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    send(question);
  }

  function toggleVoice() {
    if (listening) {
      recognition.current?.stop();
      return;
    }
    const Recognition = speechRecognitionConstructor();
    if (!Recognition) {
      setVoiceMessage(t('assistant:voice.unsupported'));
      return;
    }
    const instance = new Recognition();
    instance.lang = SPEECH_LANG;
    instance.interimResults = true;
    instance.continuous = false;
    instance.maxAlternatives = 1;
    instance.onresult = (event) => {
      const transcript = Array.from(event.results, (result) => result[0]?.transcript ?? '').join(' ');
      setQuestion(transcript.slice(0, MAX_QUESTION));
    };
    instance.onerror = (event) => {
      const key = speechErrorKey(event.error);
      setVoiceMessage(key ? t(`assistant:voice.${key}`) : null);
    };
    instance.onend = () => setListening(false);
    recognition.current = instance;
    setVoiceMessage(null);
    setListening(true);
    try {
      instance.start();
    } catch {
      setListening(false);
      setVoiceMessage(t('assistant:voice.error'));
    }
  }

  return (
    <section className="mx-auto flex max-w-4xl flex-col space-y-5">
      <div className="flex items-start">
        <span aria-hidden="true" className="mr-4 flex h-12 w-12 flex-none items-center justify-center rounded-2xl bg-brand text-accent shadow-panel">
          <Sparkles className="h-6 w-6" />
        </span>
        <div className="min-w-0 space-y-2">
          <h1 className="text-3xl text-ink sm:text-4xl">{t('assistant:title')}</h1>
          <div aria-hidden="true" className="h-0.5 w-12 rounded-full bg-accent" />
          <p className="text-sm text-ink-muted sm:text-base">{t('assistant:intro')}</p>
        </div>
      </div>

      {status.data && !status.data.globallyEnabled && (
        <p role="status" className="rounded-lg border-l-4 border-accent bg-accent-soft p-3 text-sm">{t('assistant:status.disabled')}</p>
      )}
      {status.data?.globallyEnabled && !status.data.configured && (
        <p role="status" className="rounded-lg border-l-4 border-accent bg-accent-soft p-3 text-sm">{t('assistant:status.notConfigured')}</p>
      )}
      {status.data?.globallyEnabled && status.data.configured && !status.data.businessConsent && (
        <div role="status" className="rounded-lg border-l-4 border-accent bg-accent-soft p-3 text-sm">
          <p>{status.data.canManageConsent ? t('assistant:status.notEnabledOwner') : t('assistant:status.notEnabled')}</p>
          {hasPermission(me, 'AI_CONSENT_MANAGE') && (
            <Link to="/settings" className={`${secondaryButtonClassName} mt-2 text-sm`}>
              {t('assistant:status.goToSettings')}
            </Link>
          )}
        </div>
      )}

      <div className={`${panelClassName} min-h-[18rem] space-y-4 p-4 sm:p-6`} aria-live="polite">
        {messages.length === 0 && (
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.12em] text-accent-strong">{t('assistant:suggestionsTitle')}</p>
            <ul className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
              {SUGGESTIONS.map((key) => (
                <li key={key}>
                  <button
                    type="button"
                    disabled={!ready}
                    onClick={() => send(t(`assistant:suggestions.${key}`))}
                    className="flex h-full w-full items-start rounded-xl border border-line bg-surface/60 px-4 py-3 text-left text-sm transition-colors hover:border-accent hover:bg-accent-soft focus:outline-none focus-visible:ring-2 focus-visible:ring-brand disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    <Sparkles aria-hidden="true" className="mr-2 mt-0.5 h-4 w-4 flex-none text-accent-strong" />
                    <span>{t(`assistant:suggestions.${key}`)}</span>
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}
        {messages.map((message) => (
          <div key={message.id} className={message.role === 'user' ? 'flex justify-end' : 'flex justify-start'}>
            <div
              className={`max-w-[85%] rounded-2xl px-4 py-3 text-sm leading-relaxed ${
                message.role === 'user'
                  ? 'rounded-br-md bg-brand text-brand-contrast'
                  : 'rounded-bl-md border border-line border-l-2 border-l-accent bg-sheet text-ink shadow-panel'
              }`}
            >
              <p className="mb-1 flex items-center text-xs font-semibold opacity-80">
                {message.role === 'assistant' && <Bot aria-hidden="true" className="mr-1 h-3.5 w-3.5" />}
                {message.role === 'user' ? t('assistant:you') : t('assistant:assistant')}
              </p>
              <p className="whitespace-pre-line">{message.text}</p>
              {message.insufficientData && <p className="mt-1 text-xs italic">{t('assistant:insufficientData')}</p>}
              {message.proposal && (
                <ProposalCard
                  proposal={message.proposal}
                  onDone={() =>
                    setMessages((current) => current.map((item) => (item.id === message.id ? { ...item, proposal: null } : item)))
                  }
                />
              )}
            </div>
          </div>
        ))}
        {ask.isPending && (
          <p role="status" className="flex items-center text-sm text-ink-muted">
            <Loader2 aria-hidden="true" className="mr-2 h-4 w-4 animate-spin" />
            <span>{t('assistant:sending')}</span>
          </p>
        )}
        {ask.isError && <ApiErrorMessage error={ask.error} />}
        <div ref={endRef} />
      </div>

      <form onSubmit={onSubmit} className="space-y-2">
        <label htmlFor={inputId} className="block text-sm font-medium">
          {t('assistant:inputLabel')}
        </label>
        <div className={`${panelClassName} flex items-end p-2 focus-within:ring-2 focus-within:ring-accent/60`}>
          <textarea
            id={inputId}
            rows={2}
            maxLength={MAX_QUESTION}
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                send(question);
              }
            }}
            placeholder={t('assistant:placeholder')}
            className="min-h-touch flex-1 resize-none rounded-lg border-0 bg-transparent px-3 py-2 placeholder:text-ink-muted/70 focus:outline-none"
          />
          <button
            type="button"
            onClick={toggleVoice}
            aria-pressed={listening}
            aria-label={listening ? t('assistant:voice.stop') : t('assistant:voice.start')}
            title={listening ? t('assistant:voice.stop') : t('assistant:voice.start')}
            className={`ml-2 inline-flex min-h-touch min-w-touch items-center justify-center rounded-lg border focus:outline-none focus-visible:ring-2 focus-visible:ring-brand ${
              listening ? 'animate-pulse border-brand bg-brand text-accent' : 'border-line bg-sheet text-ink hover:bg-brand-soft'
            }`}
          >
            {listening ? <MicOff aria-hidden="true" className="h-5 w-5" /> : <Mic aria-hidden="true" className="h-5 w-5" />}
          </button>
          <button
            type="submit"
            disabled={!ready || ask.isPending || question.trim() === ''}
            aria-label={t('assistant:send')}
            className={`${buttonClassName('primary')} ml-2 w-11 px-0`}
          >
            <Send aria-hidden="true" className="h-5 w-5" />
          </button>
        </div>
        {listening && (
          <p role="status" className="text-sm font-medium">
            {t('assistant:voice.listening')}
          </p>
        )}
        {voiceMessage && (
          <p role="alert" className="text-sm font-medium text-danger">
            {voiceMessage}
          </p>
        )}
        {!speechSupported && <p className="text-xs text-ink-muted">{t('assistant:voice.unsupported')}</p>}
        <p className="text-xs text-ink-muted">{t('assistant:voice.privacy')}</p>
        <div className="flex items-center justify-between">
          <p className="text-xs text-ink-muted">{t('assistant:historyNote')}</p>
          {messages.length > 0 && (
            <button type="button" onClick={() => setMessages([])} className="inline-flex items-center text-xs underline">
              <Trash2 aria-hidden="true" className="mr-1 h-3.5 w-3.5" />
              {t('assistant:clear')}
            </button>
          )}
        </div>
      </form>
    </section>
  );
}
