import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, tap, throwError } from 'rxjs';
import { BackendStatusService } from './backend-status.service';

export const httpErrorInterceptor: HttpInterceptorFn = (req, next) => {
  const status = inject(BackendStatusService);

  return next(req).pipe(
    tap({ next: () => status.markOnline() }),
    catchError((err: HttpErrorResponse) => {
      if (err.status === 0) {
        status.markOffline();
      } else {
        status.markOnline();
      }
      return throwError(() => err);
    })
  );
};
