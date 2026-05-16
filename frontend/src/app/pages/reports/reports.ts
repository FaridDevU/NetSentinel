import { Component, OnInit, computed, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../../pipes/translate.pipe';

export interface StoredReport {
  scanId: string;
  target: string;
  date: string;
  report: string;
  riskLevel?: string;
}

interface ReportItem { type: 'p' | 'li' | 'h4'; text: string; }
interface ReportSection { title: string; items: ReportItem[]; }

@Component({
  selector: 'app-reports',
  imports: [DatePipe, RouterLink, TranslatePipe],
  templateUrl: './reports.html',
  styleUrl: './reports.scss',
})
export class ReportsPage implements OnInit {
  reports = signal<StoredReport[]>([]);
  selectedId = signal<string | null>(null);
  printing = signal<StoredReport | null>(null);

  parsedSections = computed<ReportSection[]>(() => {
    const id = this.selectedId();
    if (!id) return [];
    const r = this.reports().find(rep => rep.scanId === id);
    return r ? this.parseReport(r.report) : [];
  });

  printSections = computed<ReportSection[]>(() => {
    const r = this.printing();
    return r ? this.parseReport(r.report) : [];
  });

  ngOnInit(): void {
    this.loadReports();
  }

  loadReports(): void {
    const all: StoredReport[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key?.startsWith('ns_report_')) {
        try {
          const val = localStorage.getItem(key);
          if (val) all.push(JSON.parse(val) as StoredReport);
        } catch { /* skip corrupted entries */ }
      }
    }
    all.sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime());
    this.reports.set(all);
  }

  toggleView(id: string): void {
    this.selectedId.update(curr => curr === id ? null : id);
  }

  deleteReport(scanId: string, event: Event): void {
    event.stopPropagation();
    localStorage.removeItem(`ns_report_${scanId}`);
    if (this.selectedId() === scanId) this.selectedId.set(null);
    this.loadReports();
  }

  exportPdf(report: StoredReport, event: Event): void {
    event.stopPropagation();
    this.printing.set(report);
    requestAnimationFrame(() => {
      window.print();
      setTimeout(() => this.printing.set(null), 800);
    });
  }

  riskClass(level?: string): string {
    return level?.toLowerCase() ?? 'info';
  }

  private parseReport(text: string): ReportSection[] {
    const sections: ReportSection[] = [];
    let current: ReportSection | null = null;

    for (const line of text.split('\n')) {
      const t = line.trim();
      if (t.startsWith('## ')) {
        if (current) sections.push(current);
        current = { title: this.stripMd(t.slice(3)), items: [] };
      } else if (t.startsWith('### ')) {
        current?.items.push({ type: 'h4', text: this.stripMd(t.slice(4)) });
      } else if (/^[-*] /.test(t)) {
        current?.items.push({ type: 'li', text: this.stripMd(t.slice(2)) });
      } else if (/^\d+\. /.test(t)) {
        current?.items.push({ type: 'li', text: this.stripMd(t.replace(/^\d+\. /, '')) });
      } else if (t && t !== '---' && t !== '***' && !t.startsWith('# ')) {
        if (!current) current = { title: '', items: [] };
        current.items.push({ type: 'p', text: this.stripMd(t) });
      }
    }
    if (current) sections.push(current);
    return sections;
  }

  private stripMd(text: string): string {
    return text.replace(/\*\*(.*?)\*\*/g, '$1').replace(/\*(.*?)\*/g, '$1').replace(/`(.*?)`/g, '$1');
  }
}
