import { Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-settings',
  imports: [FormsModule],
  templateUrl: './settings.html',
  styleUrl: './settings.scss',
})
export class SettingsPage implements OnInit {
  private static readonly STORAGE_KEY = 'ns_claude_key';

  apiKey = '';
  saved = signal(false);

  get hasKey(): boolean {
    return this.apiKey.trim().length > 0;
  }

  get maskedKey(): string {
    const k = this.apiKey.trim();
    if (k.length < 10) return k;
    return k.slice(0, 7) + '•'.repeat(Math.min(k.length - 10, 20)) + k.slice(-4);
  }

  ngOnInit(): void {
    this.apiKey = localStorage.getItem(SettingsPage.STORAGE_KEY) ?? '';
  }

  save(): void {
    const key = this.apiKey.trim();
    if (!key) return;
    localStorage.setItem(SettingsPage.STORAGE_KEY, key);
    this.saved.set(true);
    setTimeout(() => this.saved.set(false), 2500);
  }

  clear(): void {
    localStorage.removeItem(SettingsPage.STORAGE_KEY);
    this.apiKey = '';
    this.saved.set(false);
  }

  static getStoredKey(): string | null {
    return localStorage.getItem(SettingsPage.STORAGE_KEY);
  }
}
