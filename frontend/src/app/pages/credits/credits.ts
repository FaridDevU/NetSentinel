import { Component } from '@angular/core';
import { TranslatePipe } from '../../pipes/translate.pipe';

@Component({
  selector: 'app-credits',
  imports: [TranslatePipe],
  templateUrl: './credits.html',
  styleUrl: './credits.scss',
})
export class CreditsPage {}
