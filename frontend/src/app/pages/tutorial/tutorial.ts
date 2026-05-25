import { Component, inject } from '@angular/core';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { TutorialService } from '../../services/tutorial.service';

@Component({
  selector: 'app-tutorial',
  imports: [TranslatePipe],
  templateUrl: './tutorial.html',
  styleUrl: './tutorial.scss',
})
export class TutorialPage {
  readonly tutorial = inject(TutorialService);
}
