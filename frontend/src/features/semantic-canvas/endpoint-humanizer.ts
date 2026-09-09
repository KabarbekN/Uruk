export interface EndpointInfo {
  id: string;
  label: string;
  method?: string | null;
  path?: string | null;
  methodName?: string | null;
  aiTitle?: string | null;
  aiDescription?: string | null;
  hasAi?: boolean | null;
}

export interface HumanizedEndpoint {
  title: string;
  description: string;
  method: string;
  path: string;
  methodName: string;
}

// Словарь известных действий контроллеров
const METHOD_VERB_MAP: Record<string, string> = {
  // Поездки и маршруты (TripController)
  departOrganization: 'Начало поездки по событию организации',
  departWorkShift: 'Начало поездки по рабочей смене',
  departPersonal: 'Начало персональной поездки',
  arrive: 'Прибытие в пункт назначения (завершение поездки)',
  cancel: 'Отмена активной поездки / заявки',
  complete: 'Успешное завершение поездки',
  delay: 'Фиксация задержки рейса в пути',
  heartbeat: 'Телеметрия / пинг активности водителя',
  location: 'Отправка текущей геолокации транспорта',
  locationBatch: 'Пакетная передача точек маршрута',
  locationBatchReceipt: 'Получение квитанции пакета координат',
  pause: 'Временная приостановка поездки',
  resume: 'Возобновление приостановленной поездки',
  eta: 'Расчёт ожидаемого времени прибытия (ETA)',
  sharing: 'Параметры совместного доступа к поездке',
  updateSharing: 'Изменение настроек совместного доступа',
  revokeSharing: 'Полный отзыв совместного доступа',
  viewers: 'Список активных наблюдателей за рейсом',
  grantViewer: 'Предоставление доступа наблюдателю',
  revokeViewer: 'Отзыв доступа наблюдателя к поездке',
  publicShares: 'Список публичных ссылок для трекинга',
  createPublicShare: 'Создание публичной ссылки для трекинга',
  revokePublicShare: 'Удаление публичной ссылки на поездку',
  publicShare: 'Просмотр поездки по публичной ссылке',

  // Авторизация и пользователи
  login: 'Авторизация в системе (вход)',
  logout: 'Выход из учетной записи',
  register: 'Регистрация новой учетной записи',
  refresh: 'Обновление сессии и токена доступа',
  requestOtp: 'Запрос одноразового кода подтверждения (OTP)',
  startTelegramLogin: 'Инициализация входа через Telegram',
  completeTelegramLogin: 'Завершение авторизации через Telegram',

  // События и посещаемость
  checkIn: 'Отметка о прибытии участника (Check-In)',
  attendance: 'Журнал посещаемости события',
  participants: 'Список участников события',
  add: 'Добавление нового участника',
  remove: 'Исключение участника из состава',
  polls: 'Список голосований и опросов события',
  createPoll: 'Создание нового опроса для участников',
  readiness: 'Проверка готовности к событию',
  reconfirm: 'Повторное подтверждение участия',
  eventDashboard: 'Сводный дашборд мониторинга события',
  eventChanges: 'История изменений события',
  eventCarpools: 'Список совместных поездок (карпулов)',

  // Карпулы (CarpoolController)
  passenger: 'Посадка или высадка пассажира',
  requests: 'Заявки пассажиров на совместную поездку',
  request: 'Создание заявки на участие / корректировку',
  respond: 'Ответ на заявку (принять или отклонить)',
  route: 'Построение маршрута совместной поездки',
  transition: 'Смена жизненного цикла поездки (старт/финиш)',

  // Видеонаблюдение и камеры (CameraController)
  streamResource: 'Трансляция прямого видеопотока с камеры',
  playback: 'Воспроизведение видеозаписи из архива',
  stream: 'Прямой видеопоток с камеры наблюдения',
  streamProxy: 'Защищенное проксирование видеопотока',
  map: 'Отображение видеокамер на карте местности',
  snapshot: 'Получение стоп-кадра с камеры',
  status: 'Проверка статуса доступности устройства',
  cameras: 'Реестр камер видеонаблюдения',

  // Датасеты и аналитика (DatasetController)
  datasets: 'Управление наборами данных (датасетами)',
  exportDataset: 'Выгрузка и экспорт данных',
  importDataset: 'Импорт набора данных в систему',

  // Общие CRUD операции
  get: 'Получение детальной информации по ID',
  list: 'Получение постраничного списка записей',
  findAll: 'Получение полного реестра записей',
  findById: 'Поиск записи по первичному ключу',
  create: 'Создание новой записи в системе',
  update: 'Обновление данных существующей записи',
  delete: 'Удаление записи из базы данных',
  send: 'Отправка сообщения или уведомления',
  revoke: 'Аннулирование / отзыв прав доступа',
};

// Семантические паттерны путей
const PATH_ACTION_PATTERNS: Array<{ pattern: RegExp; title: (match: RegExpMatchArray, method: string) => string }> = [
  {
    pattern: /\/departure$/i,
    title: () => 'Запуск / начало поездки по маршруту',
  },
  {
    pattern: /\/arrive$/i,
    title: () => 'Фиксация прибытия в точку назначения',
  },
  {
    pattern: /\/eta$/i,
    title: () => 'Расчёт расчетного времени прибытия (ETA)',
  },
  {
    pattern: /\/resume$/i,
    title: () => 'Возобновление приостановленной поездки',
  },
  {
    pattern: /\/pause$/i,
    title: () => 'Приостановка активного рейса',
  },
  {
    pattern: /\/viewers\/\{[^}]+\}$/i,
    title: (_m, method) => method === 'DELETE' ? 'Отзыв прав наблюдателя за поездкой' : 'Управление наблюдателем поездки',
  },
  {
    pattern: /\/public-shares\/\{[^}]+\}$/i,
    title: (_m, method) => method === 'DELETE' ? 'Удаление публичной ссылки на поездку' : 'Управление публичной ссылкой',
  },
  {
    pattern: /\/public-shares$/i,
    title: (_m, method) => method === 'POST' ? 'Создание публичной ссылки для трекинга' : 'Список публичных ссылок',
  },
  {
    pattern: /\/sharing$/i,
    title: (_m, method) => method === 'PATCH' ? 'Обновление настроек шеринга поездки' : 'Параметры совместного доступа',
  },
  {
    pattern: /\/push-token$/i,
    title: (_m, method) => method === 'DELETE' ? 'Удаление push-токена устройства' : 'Регистрация push-токена устройства',
  },
  {
    pattern: /\/check-in$/i,
    title: () => 'Регистрация присутствия (Check-In)',
  },
  {
    pattern: /\/participants\/\{[^}]+\}$/i,
    title: (_m, method) => method === 'DELETE' ? 'Исключение участника из события' : 'Обновление статуса участника',
  },
  {
    pattern: /\/invitations\/\{[^}]+\}$/i,
    title: (_m, method) => method === 'DELETE' ? 'Отзыв приглашения на событие' : 'Информация о приглашении',
  },
  {
    pattern: /\/corrections$/i,
    title: () => 'Запрос на корректировку посещаемости',
  },
  {
    pattern: /\/passengers\/\{[^}]+\}\/\{action:[^}]+\}/i,
    title: () => 'Управление посадкой и высадкой пассажира',
  },
  {
    pattern: /\/organizations\/directory$/i,
    title: () => 'Справочник активных организаций',
  },
  {
    pattern: /\/organizations\/\{[^}]+\}\/restore$/i,
    title: () => 'Восстановление удалённой организации',
  },
  {
    pattern: /\/organizations\/\{[^}]+\}$/i,
    title: (_m, method) =>
      method === 'DELETE'
        ? 'Удаление организации из системы'
        : method === 'PUT' || method === 'PATCH'
          ? 'Обновление данных организации'
          : 'Карточка и реквизиты организации',
  },
  {
    pattern: /\/organizations$/i,
    title: (_m, method) =>
      method === 'POST'
        ? 'Создание новой организации'
        : 'Реестр и поиск организаций',
  },
  {
    pattern: /\/cameras\/\{[^}]+\}\/stream/i,
    title: () => 'Трансляция видеопотока с камеры',
  },
  {
    pattern: /\/cameras\/\{[^}]+\}\/playback/i,
    title: () => 'Воспроизведение архива видеозаписи',
  },
  {
    pattern: /\/cameras\/map$/i,
    title: () => 'Отображение видеокамер на интерактивной карте',
  },
  {
    pattern: /\/cameras\/my$/i,
    title: () => 'Список видеокамер текущего пользователя',
  },
  {
    pattern: /\/cameras\/\{[^}]+\}$/i,
    title: (_m, method) =>
      method === 'DELETE'
        ? 'Удаление камеры из системы'
        : method === 'PUT' || method === 'PATCH'
          ? 'Обновление параметров камеры'
          : 'Просмотр параметров камеры',
  },
  {
    pattern: /\/cameras$/i,
    title: (_m, method) =>
      method === 'POST'
        ? 'Регистрация новой видеокамеры'
        : 'Реестр камер видеонаблюдения',
  },
  {
    pattern: /\/auth\/register$/i,
    title: () => 'Регистрация нового пользователя в системе',
  },
  {
    pattern: /\/auth\/login$/i,
    title: () => 'Вход в систему (аутентификация)',
  },
  {
    pattern: /\/auth\/refresh$/i,
    title: () => 'Продление рабочей сессии пользователя',
  },
  {
    pattern: /\/auth\/logout$/i,
    title: () => 'Завершение сеанса (выход)',
  },
  {
    pattern: /\/users\/me$/i,
    title: () => 'Профиль и настройки текущего пользователя',
  },
  {
    pattern: /\/users\/\{[^}]+\}$/i,
    title: (_m, method) =>
      method === 'DELETE'
        ? 'Удаление пользователя'
        : method === 'PUT' || method === 'PATCH'
          ? 'Обновление профиля пользователя'
          : 'Карточка пользователя',
  },
  {
    pattern: /\/users$/i,
    title: (_m, method) =>
      method === 'POST' ? 'Создание учетной записи' : 'Реестр пользователей системы',
  },
  {
    pattern: /\/zones\/\{[^}]+\}$/i,
    title: (_m, method) =>
      method === 'DELETE' ? 'Удаление гео-зоны' : 'Параметры гео-зоны',
  },
  {
    pattern: /\/zones$/i,
    title: (_m, method) =>
      method === 'POST' ? 'Создание новой гео-зоны' : 'Реестр гео-зон местности',
  },
  {
    pattern: /\/datasets/i,
    title: (_m, method) =>
      method === 'POST' ? 'Создание набора данных' : 'Реестр наборов данных',
  },
];

const ENTITY_NAMES_RU: Record<string, { single: string; plural: string; genitive: string }> = {
  organizations: { single: 'организация', plural: 'организации', genitive: 'организаций' },
  organization: { single: 'организация', plural: 'организации', genitive: 'организации' },
  cameras: { single: 'видеокамера', plural: 'видеокамеры', genitive: 'видеокамер' },
  camera: { single: 'видеокамера', plural: 'видеокамеры', genitive: 'видеокамеры' },
  users: { single: 'пользователь', plural: 'пользователи', genitive: 'пользователей' },
  user: { single: 'пользователь', plural: 'пользователи', genitive: 'пользователя' },
  zones: { single: 'гео-зона', plural: 'гео-зоны', genitive: 'гео-зон' },
  zone: { single: 'гео-зона', plural: 'гео-зоны', genitive: 'гео-зоны' },
  devices: { single: 'устройство', plural: 'устройства', genitive: 'устройств' },
  device: { single: 'устройство', plural: 'устройства', genitive: 'устройства' },
  events: { single: 'событие', plural: 'события', genitive: 'событий' },
  event: { single: 'событие', plural: 'события', genitive: 'события' },
  trips: { single: 'поездка', plural: 'поездки', genitive: 'поездок' },
  trip: { single: 'поездка', plural: 'поездки', genitive: 'поездки' },
  carpools: { single: 'совместная поездка', plural: 'совместные поездки', genitive: 'совместных поездок' },
  auth: { single: 'авторизация', plural: 'авторизация', genitive: 'авторизации' },
  roles: { single: 'роль доступа', plural: 'роли доступа', genitive: 'ролей доступа' },
  permissions: { single: 'право доступа', plural: 'права доступа', genitive: 'прав доступа' },
  files: { single: 'файл', plural: 'файлы', genitive: 'файлов' },
};

export function isBadAiTitle(title?: string | null): boolean {
  if (!title || !title.trim()) return true;
  const t = title.trim();
  // Bug report / diagnostics hallucinations
  if (/missing required field/i.test(t)) return true;
  if (/identify missing/i.test(t)) return true;
  if (/\b(?:table|column|database schema)\b/i.test(t)) return true;
  if (/service:\s*\w+/i.test(t)) return true;
  if (/\b(?:validation|entity validation|resource path validation)\b/i.test(t)) return true;
  if (/\b(?:service method)\b/i.test(t)) return true;
  // All English text with code identifiers
  if (/^[A-Za-z0-9\s:_{}.,'"()\-+]+$/.test(t) && /[a-z]{4,}/i.test(t)) {
    return true;
  }
  return false;
}

export function humanizeEndpoint(ep: EndpointInfo): HumanizedEndpoint {
  const method = (ep.method || 'GET').toUpperCase();
  const path = ep.path || ep.label || '';
  const methodName = ep.methodName || '';

  // 1. Приоритет: AI-заголовок, ТОЛЬКО если он качественный и не содержит технического мусора
  if (ep.aiTitle && !isBadAiTitle(ep.aiTitle)) {
    return {
      title: ep.aiTitle.trim(),
      description: ep.aiDescription?.trim() || `Выполняется для пути ${path}`,
      method,
      path,
      methodName,
    };
  }

  // 2. Проверка точного совпадения имени метода
  if (methodName && METHOD_VERB_MAP[methodName]) {
    return {
      title: METHOD_VERB_MAP[methodName],
      description: `Контроллер выполняет операцию «${methodName}» для пути ${path}`,
      method,
      path,
      methodName,
    };
  }

  // 3. Проверка регулярных выражений по пути
  for (const { pattern, title } of PATH_ACTION_PATTERNS) {
    const match = path.match(pattern);
    if (match) {
      return {
        title: title(match, method),
        description: `Маршрут: ${path}`,
        method,
        path,
        methodName,
      };
    }
  }

  // 4. Семантический разбор URL и глагола
  const rawSegments = path
    .split('/')
    .filter((s) => s.length > 0 && s !== 'api' && s !== 'v1');
  const isDetail = rawSegments.length > 0 && rawSegments[rawSegments.length - 1]!.startsWith('{');
  const nonParamSegments = rawSegments.filter((s) => !s.startsWith('{'));
  const lastEntity = nonParamSegments[nonParamSegments.length - 1]?.toLowerCase() || 'данных';
  const entityInfo = ENTITY_NAMES_RU[lastEntity];

  let title = '';
  if (entityInfo) {
    if (method === 'GET') {
      title = isDetail ? `Карточка и параметры: ${entityInfo.single}` : `Реестр и поиск: ${entityInfo.plural}`;
    } else if (method === 'POST') {
      title = `Создание: ${entityInfo.single}`;
    } else if (method === 'PUT' || method === 'PATCH') {
      title = `Обновление данных: ${entityInfo.single}`;
    } else if (method === 'DELETE') {
      title = `Удаление: ${entityInfo.single}`;
    }
  } else {
    let verbPrefix = 'Просмотр данных';
    if (method === 'POST') verbPrefix = 'Создание записи';
    else if (method === 'PUT') verbPrefix = 'Полное обновление';
    else if (method === 'PATCH') verbPrefix = 'Изменение данных';
    else if (method === 'DELETE') verbPrefix = 'Удаление';
    title = `${verbPrefix} «${lastEntity}»`;
  }

  return {
    title,
    description: `Маршрут: ${path}`,
    method,
    path,
    methodName,
  };
}
