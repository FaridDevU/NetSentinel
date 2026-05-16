import { Pipe, PipeTransform } from '@angular/core';
import { LangService } from '../services/lang.service';

@Pipe({ name: 't', pure: false, standalone: true })
export class TranslatePipe implements PipeTransform {
  constructor(private lang: LangService) {}

  transform(key: string): string {
    return this.lang.t(key);
  }
}
