import { Component, inject } from '@angular/core';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { TutorialService } from '../../services/tutorial.service';
import { LucideChartNoAxesColumn, LucideClipboardCheck, LucidePlay, LucideScanSearch } from '@lucide/angular';

@Component({
  selector: 'app-tutorial',
  imports: [TranslatePipe, LucideChartNoAxesColumn, LucideClipboardCheck, LucidePlay, LucideScanSearch],
  templateUrl: './tutorial.html',
  styleUrl: './tutorial.scss',
})
export class TutorialPage {
  readonly tutorial = inject(TutorialService);
}
