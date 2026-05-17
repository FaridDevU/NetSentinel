import { Injectable, signal } from '@angular/core';

const TR: Record<'es' | 'en', Record<string, string>> = {
  es: {
    'nav.newScan': 'Escanear',
    'nav.history': 'Historial',
    'nav.settings': 'Ajustes',
    'nav.reports': 'Informes',
    'offline.banner': 'Servicio no disponible — asegurate de que el backend este corriendo en el puerto 8080',

    // Scan page
    'scan.title': 'Analizar mi red',
    'scan.subtitle': 'Detecta problemas de seguridad en todos los dispositivos conectados a tu red',
    'scan.network.detecting': 'Detectando tu red...',
    'scan.network.detected': 'Red detectada',
    'scan.network.change': 'Cambiar',
    'scan.network.choose': 'Elige la red que quieres analizar',
    'scan.network.retry': 'Volver a detectar',
    'scan.network.none': 'No se detecto ninguna red automaticamente.',
    'scan.manual.label': 'Direccion de red o dispositivo',
    'scan.manual.placeholder': '192.168.1.0/24  o  192.168.1.1',
    'scan.manual.hint': 'Escribe la IP de tu router o el rango de tu red',
    'scan.manual.link': 'Ingresar manualmente',
    'scan.start': 'Analizar mi red',
    'scan.cancel': 'Cancelar',
    'scan.newScan': 'Nuevo analisis',
    'scan.step.searching': 'Buscando dispositivos en tu red...',
    'scan.step.found': 'Dispositivos encontrados — revisando seguridad...',
    'scan.step.checking': 'Consultando base de datos de vulnerabilidades...',
    'scan.step.analyzing': 'Preparando tu diagnostico de seguridad...',
    'scan.showDetails': 'Ver progreso tecnico',
    'scan.hideDetails': 'Ocultar detalles',

    // Results page — hero
    'results.risk.critical': 'Tu red tiene problemas criticos',
    'results.risk.critical.sub': 'Requiere atencion inmediata para proteger tu negocio',
    'results.risk.high': 'Tu red tiene riesgos importantes',
    'results.risk.high.sub': 'Actua pronto para proteger tus datos y dispositivos',
    'results.risk.medium': 'Tu red tiene algunos problemas',
    'results.risk.medium.sub': 'Revisa las recomendaciones a continuacion',
    'results.risk.low': 'Tu red esta bien',
    'results.risk.low.sub': 'Solo hay detalles menores que revisar',
    'results.risk.info': 'Tu red esta segura',
    'results.risk.info.sub': 'No se encontraron problemas de seguridad',
    'results.hero.devices': 'dispositivo(s) analizado(s)',
    'results.hero.issues': 'problema(s) encontrado(s)',

    // Results page — sections
    'results.whattodo': 'Que debes hacer',
    'results.problems': 'Problemas encontrados',
    'results.noproblems': 'No se encontraron problemas de seguridad en tu red.',
    'results.technical.toggle': 'Ver detalles tecnicos',
    'results.technical.hide': 'Ocultar detalles tecnicos',
    'results.technical.title': 'Detalles tecnicos — Dispositivos y puertos',
    'results.scanDate': 'Analisis del',
    'results.duration': 'Duracion',

    // Results page — severity labels
    'severity.critical': 'Critico',
    'severity.high': 'Alto',
    'severity.medium': 'Medio',
    'severity.low': 'Bajo',
    'severity.info': 'Info',

    // Results page — existing
    'results.back': 'Volver',
    'results.loading': 'Cargando resultados...',
    'results.noHosts': 'No se encontraron dispositivos en este escaneo.',
    'results.noPorts': 'Sin puertos reportados.',
    'results.col.port': 'Puerto',
    'results.col.state': 'Estado',
    'results.col.service': 'Servicio',
    'results.col.version': 'Version',
    'results.col.cves': 'Vulnerabilidades',
    'results.mac': 'MAC',
    'results.vendor': 'Fabricante',
    'results.openPorts': 'servicio(s) activo(s)',
    'results.cves': 'vulnerabilidad(es)',
    'results.ai.title': 'Informe detallado con IA',
    'results.ai.desc': 'Claude analiza los datos del escaneo y genera un informe personalizado con rutas de ataque y pasos concretos de remediacion.',
    'results.ai.generate': 'Generar informe con IA',
    'results.ai.noKeyText': 'Configura tu clave API de Anthropic en',
    'results.ai.noKeyLink': 'Ajustes',
    'results.ai.noKeyEnd': 'para habilitar el analisis con IA.',
    'results.ai.generating': 'Generando informe...',
    'results.ai.relatedCves': 'Vulnerabilidades relacionadas:',
    'results.ai.regenerate': 'Regenerar',

    // History page
    'history.title': 'Historial de analisis',
    'history.scans': 'analisis',
    'history.loading': 'Cargando...',
    'history.empty': 'No hay analisis registrados.',
    'history.startFirst': 'Realiza tu primer analisis',
    'history.col.target': 'Red analizada',
    'history.col.status': 'Estado',
    'history.col.started': 'Fecha',
    'history.col.duration': 'Duracion',
    'history.prev': 'Anterior',
    'history.next': 'Siguiente',
    'history.delete': 'Eliminar',
    'history.error': 'No se pudo cargar el historial. Asegurate de que el servidor este activo.',

    // Status labels
    'status.pending': 'Iniciando',
    'status.running': 'Analizando',
    'status.completed': 'Completado',
    'status.failed': 'Error',
    'status.cancelled': 'Cancelado',

    // Settings page
    'settings.title': 'Ajustes',
    'settings.subtitle': 'Configuracion de integraciones opcionales',
    'settings.lang.title': 'Idioma',
    'settings.lang.desc': 'Cambia el idioma de la interfaz entre espanol e ingles.',
    'settings.ai.title': 'Analisis con Inteligencia Artificial',
    'settings.ai.configured': 'Configurada',
    'settings.ai.notConfigured': 'No configurada',
    'settings.ai.desc': 'Con tu clave API de Anthropic, NetSentinel puede generar un informe personalizado que explica cada problema en lenguaje simple y te indica exactamente que hacer. Tu clave se guarda solo en este dispositivo.',
    'settings.ai.keyLabel': 'Clave API de Anthropic',
    'settings.ai.keyHint': 'Obtenla en console.anthropic.com',
    'settings.ai.saved': 'Guardado',
    'settings.ai.clear': 'Borrar clave',
    'settings.ai.save': 'Guardar',
    'settings.how.title': 'Como funciona',
    'settings.how.step1.strong': 'Haz clic en "Analizar mi red"',
    'settings.how.step1.text': '— NetSentinel encuentra todos los dispositivos conectados y detecta sus servicios automaticamente',
    'settings.how.step2.strong': 'Analisis automatico',
    'settings.how.step2.text': '— el motor integrado calcula el riesgo y genera recomendaciones sin costo adicional',
    'settings.how.step3.strong': 'Informe con IA (opcional)',
    'settings.how.step3.text': '— con tu clave API, Claude genera un informe mas detallado con explicaciones en lenguaje simple',

    // Reports page
    'reports.title': 'Informes guardados',
    'reports.subtitle': 'Informes generados por IA a partir de tus analisis. Puedes exportarlos en PDF.',
    'reports.empty': 'No hay informes guardados. Realiza un analisis y genera un informe con IA.',
    'reports.view': 'Ver',
    'reports.close': 'Cerrar',
    'reports.pdf': 'Exportar PDF',
    'reports.delete': 'Eliminar',
    'reports.risk': 'Nivel de riesgo',
    'reports.noRisk': 'N/A',
  },
  en: {
    'nav.newScan': 'Scan',
    'nav.history': 'History',
    'nav.settings': 'Settings',
    'nav.reports': 'Reports',
    'offline.banner': 'Service unavailable — make sure the backend is running on port 8080',

    // Scan page
    'scan.title': 'Analyze my network',
    'scan.subtitle': 'Detect security issues in all devices connected to your network',
    'scan.network.detecting': 'Detecting your network...',
    'scan.network.detected': 'Network detected',
    'scan.network.change': 'Change',
    'scan.network.choose': 'Choose the network to analyze',
    'scan.network.retry': 'Detect again',
    'scan.network.none': 'No network detected automatically.',
    'scan.manual.label': 'Network address or device',
    'scan.manual.placeholder': '192.168.1.0/24  or  192.168.1.1',
    'scan.manual.hint': 'Type your router IP or your network range',
    'scan.manual.link': 'Enter manually',
    'scan.start': 'Analyze my network',
    'scan.cancel': 'Cancel',
    'scan.newScan': 'New analysis',
    'scan.step.searching': 'Looking for devices on your network...',
    'scan.step.found': 'Devices found — checking security...',
    'scan.step.checking': 'Checking vulnerability database...',
    'scan.step.analyzing': 'Preparing your security report...',
    'scan.showDetails': 'Show technical details',
    'scan.hideDetails': 'Hide details',

    // Results page — hero
    'results.risk.critical': 'Your network has critical issues',
    'results.risk.critical.sub': 'Requires immediate attention to protect your business',
    'results.risk.high': 'Your network has significant risks',
    'results.risk.high.sub': 'Act soon to protect your data and devices',
    'results.risk.medium': 'Your network has some issues',
    'results.risk.medium.sub': 'Review the recommendations below',
    'results.risk.low': 'Your network looks good',
    'results.risk.low.sub': 'Only minor details to review',
    'results.risk.info': 'Your network is secure',
    'results.risk.info.sub': 'No security issues found',
    'results.hero.devices': 'device(s) analyzed',
    'results.hero.issues': 'issue(s) found',

    // Results page — sections
    'results.whattodo': 'What to do',
    'results.problems': 'Issues found',
    'results.noproblems': 'No security issues found on your network.',
    'results.technical.toggle': 'View technical details',
    'results.technical.hide': 'Hide technical details',
    'results.technical.title': 'Technical details — Devices and ports',
    'results.scanDate': 'Scan from',
    'results.duration': 'Duration',

    // Results page — severity labels
    'severity.critical': 'Critical',
    'severity.high': 'High',
    'severity.medium': 'Medium',
    'severity.low': 'Low',
    'severity.info': 'Info',

    // Results page — existing
    'results.back': 'Back',
    'results.loading': 'Loading results...',
    'results.noHosts': 'No devices discovered in this scan.',
    'results.noPorts': 'No ports reported.',
    'results.col.port': 'Port',
    'results.col.state': 'State',
    'results.col.service': 'Service',
    'results.col.version': 'Version',
    'results.col.cves': 'Vulnerabilities',
    'results.mac': 'MAC',
    'results.vendor': 'Vendor',
    'results.openPorts': 'active service(s)',
    'results.cves': 'vulnerability(ies)',
    'results.ai.title': 'Detailed AI report',
    'results.ai.desc': 'Claude analyzes your scan data and generates a personalized report with attack paths and concrete remediation steps.',
    'results.ai.generate': 'Generate AI report',
    'results.ai.noKeyText': 'Configure your Anthropic API key in',
    'results.ai.noKeyLink': 'Settings',
    'results.ai.noKeyEnd': 'to enable AI-powered analysis.',
    'results.ai.generating': 'Generating report...',
    'results.ai.relatedCves': 'Related vulnerabilities:',
    'results.ai.regenerate': 'Regenerate',

    // History page
    'history.title': 'Analysis history',
    'history.scans': 'analyses',
    'history.loading': 'Loading...',
    'history.empty': 'No analyses recorded yet.',
    'history.startFirst': 'Run your first analysis',
    'history.col.target': 'Network scanned',
    'history.col.status': 'Status',
    'history.col.started': 'Date',
    'history.col.duration': 'Duration',
    'history.prev': 'Previous',
    'history.next': 'Next',
    'history.delete': 'Delete',
    'history.error': 'Could not load history. Make sure the server is running.',

    // Status labels
    'status.pending': 'Starting',
    'status.running': 'Analyzing',
    'status.completed': 'Completed',
    'status.failed': 'Failed',
    'status.cancelled': 'Cancelled',

    // Settings page
    'settings.title': 'Settings',
    'settings.subtitle': 'Configure optional integrations',
    'settings.lang.title': 'Language',
    'settings.lang.desc': 'Switch the interface between English and Spanish.',
    'settings.ai.title': 'AI Analysis',
    'settings.ai.configured': 'Configured',
    'settings.ai.notConfigured': 'Not configured',
    'settings.ai.desc': 'With your Anthropic API key, NetSentinel can generate a personalized report explaining each issue in plain language and telling you exactly what to do. Your key is stored only on this device.',
    'settings.ai.keyLabel': 'Anthropic API Key',
    'settings.ai.keyHint': 'Get it at console.anthropic.com',
    'settings.ai.saved': 'Saved',
    'settings.ai.clear': 'Clear key',
    'settings.ai.save': 'Save',
    'settings.how.title': 'How it works',
    'settings.how.step1.strong': 'Click "Analyze my network"',
    'settings.how.step1.text': '— NetSentinel finds all connected devices and detects their services automatically',
    'settings.how.step2.strong': 'Automatic analysis',
    'settings.how.step2.text': '— the built-in engine calculates risk and generates recommendations at no extra cost',
    'settings.how.step3.strong': 'AI report (optional)',
    'settings.how.step3.text': '— with your API key, Claude generates a more detailed report with plain-language explanations',

    // Reports page
    'reports.title': 'Saved reports',
    'reports.subtitle': 'AI-generated reports from your analyses. Export any report as PDF.',
    'reports.empty': 'No reports saved yet. Run an analysis and generate an AI report.',
    'reports.view': 'View',
    'reports.close': 'Close',
    'reports.pdf': 'Export PDF',
    'reports.delete': 'Delete',
    'reports.risk': 'Risk level',
    'reports.noRisk': 'N/A',
  },
};

@Injectable({ providedIn: 'root' })
export class LangService {
  private static readonly KEY = 'ns_lang';

  lang = signal<'es' | 'en'>(
    (localStorage.getItem(LangService.KEY) as 'es' | 'en') ?? 'es'
  );

  toggle(): void {
    const next = this.lang() === 'es' ? 'en' : 'es';
    this.lang.set(next);
    localStorage.setItem(LangService.KEY, next);
  }

  t(key: string): string {
    return TR[this.lang()][key] ?? key;
  }
}
