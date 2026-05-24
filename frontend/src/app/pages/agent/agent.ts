import { Component, ElementRef, OnDestroy, ViewChild, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ScanService } from '../../services/scan.service';
import { LangService } from '../../services/lang.service';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { AgentChatMessage, AgentSseEvent } from '../../models/scan.models';

interface ToolCallDisplay {
  name: string;
  done: boolean;
}

interface DisplayMessage {
  role: 'user' | 'assistant';
  text: string;
  toolCalls: ToolCallDisplay[];
  isStreaming: boolean;
}

@Component({
  selector: 'app-agent',
  imports: [FormsModule, TranslatePipe],
  templateUrl: './agent.html',
  styleUrl: './agent.scss',
})
export class AgentPage implements OnDestroy {
  @ViewChild('messagesRef') messagesRef?: ElementRef<HTMLDivElement>;

  private static readonly KEY_STORAGE = 'ns_api_key';

  private static readonly TOOL_LABELS: Record<string, string> = {
    detect_networks: 'Detectando redes...',
    start_scan: 'Iniciando escaneo...',
    get_scan_status: 'Consultando estado...',
    get_scan_results: 'Leyendo resultados...',
    get_scan_logs: 'Revisando logs...',
    get_history: 'Consultando historial...',
  };

  messages = signal<DisplayMessage[]>([]);
  streaming = signal(false);
  apiKey = signal(localStorage.getItem(AgentPage.KEY_STORAGE) ?? '');
  showKeySetup = signal(!localStorage.getItem(AgentPage.KEY_STORAGE));

  inputText = '';
  keyInput = '';

  private history: AgentChatMessage[] = [];
  private sub?: Subscription;

  constructor(
    private scanService: ScanService,
    public lang: LangService,
  ) {}

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  saveKey(): void {
    const key = this.keyInput.trim();
    if (!key) return;
    localStorage.setItem(AgentPage.KEY_STORAGE, key);
    this.apiKey.set(key);
    this.showKeySetup.set(false);
    this.keyInput = '';
  }

  resetKey(): void {
    localStorage.removeItem(AgentPage.KEY_STORAGE);
    this.apiKey.set('');
    this.showKeySetup.set(true);
  }

  suggest(text: string): void {
    this.inputText = text;
    this.send();
  }

  onEnter(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.send();
    }
  }

  send(): void {
    const text = this.inputText.trim();
    if (!text || this.streaming()) return;
    this.inputText = '';

    this.history.push({ role: 'user', content: text });
    this.messages.update(msgs => [
      ...msgs,
      { role: 'user', text, toolCalls: [], isStreaming: false },
    ]);

    const assistantIndex = this.messages().length;
    this.messages.update(msgs => [
      ...msgs,
      { role: 'assistant', text: '', toolCalls: [], isStreaming: true },
    ]);

    this.streaming.set(true);
    let accumulatedText = '';

    this.sub = this.scanService.streamAgentChat({
      apiKey: this.apiKey(),
      messages: [...this.history],
    }).subscribe({
      next: (event: AgentSseEvent) => {
        if (event.type === 'text') {
          accumulatedText += event.content ?? '';
          this.updateAssistant(assistantIndex, msg => ({ ...msg, text: accumulatedText }));
          this.scrollToBottom();
        } else if (event.type === 'tool_use') {
          this.updateAssistant(assistantIndex, msg => ({
            ...msg,
            toolCalls: [...msg.toolCalls, { name: event.name!, done: false }],
          }));
        } else if (event.type === 'tool_result') {
          this.updateAssistant(assistantIndex, msg => {
            const toolCalls = [...msg.toolCalls];
            for (let i = toolCalls.length - 1; i >= 0; i--) {
              if (toolCalls[i].name === event.name && !toolCalls[i].done) {
                toolCalls[i] = { ...toolCalls[i], done: true };
                break;
              }
            }
            return { ...msg, toolCalls };
          });
        } else if (event.type === 'done') {
          this.updateAssistant(assistantIndex, msg => ({ ...msg, isStreaming: false }));
          if (accumulatedText) {
            this.history.push({ role: 'assistant', content: accumulatedText });
          }
          this.streaming.set(false);
          this.scrollToBottom();
        } else if (event.type === 'error') {
          this.updateAssistant(assistantIndex, msg => ({
            ...msg,
            text: 'Error: ' + (event.message ?? 'Error desconocido'),
            isStreaming: false,
          }));
          this.streaming.set(false);
        }
      },
      error: () => {
        this.updateAssistant(assistantIndex, msg => ({
          ...msg,
          text: this.lang.t('agent.error.connection'),
          isStreaming: false,
        }));
        this.streaming.set(false);
      },
    });
  }

  toolLabel(name: string): string {
    return AgentPage.TOOL_LABELS[name] ?? name;
  }

  private updateAssistant(index: number, fn: (msg: DisplayMessage) => DisplayMessage): void {
    this.messages.update(msgs => {
      const updated = [...msgs];
      if (updated[index]) updated[index] = fn(updated[index]);
      return updated;
    });
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      const el = this.messagesRef?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    }, 0);
  }
}
