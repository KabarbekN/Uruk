export interface ControllerMeta {
  businessTitle: string;
  description: string;
  security: 'PUBLIC' | 'AUTHENTICATED' | 'ADMIN' | 'SERVICE';
  securityLabel: string;
  securityTone: 'neutral' | 'positive' | 'warning' | 'negative';
  domain: string;
}

const CONTROLLER_REGISTRY: Record<string, ControllerMeta> = {
  AttendanceCorrectionController: {
    businessTitle: 'Корректировка посещаемости и табеля',
    description: 'Подача и согласование запросов на исправление отметок посещаемости, ручные правки табеля рабочего времени.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Учет времени',
  },
  EventAttendanceController: {
    businessTitle: 'Учет присутствия на мероприятиях',
    description: 'Фиксация и валидация присутствия участников на событиях, сканирование билетов и подтверждение визита.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Мероприятия',
  },
  ShiftAttendanceController: {
    businessTitle: 'Учет рабочих смен и выходов',
    description: 'Открытие, продление и закрытие рабочих смен персонала, геолокационная отметка начала смены.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Персонал',
  },
  BillingController: {
    businessTitle: 'Биллинг, тарифы и подписки',
    description: 'Управление балансом, выбор тарифных планов, списание средств, формирование инвойсов и актов.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Финансы',
  },
  CarpoolController: {
    businessTitle: 'Совместные поездки (карпулинг)',
    description: 'Поиск попутчиков, создание совместных маршрутов, разделение стоимости поездки и бронирование мест.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Поездки',
  },
  CommunicationController: {
    businessTitle: 'Чаты и обмен сообщениями',
    description: 'Внутренняя переписка участников поездок и событий, отправка медиафайлов и системных сообщений.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Коммуникации',
  },
  EventController: {
    businessTitle: 'Управление событиями и мероприятиями',
    description: 'Создание, редактирование, публикация и планирование корпоративных и публичных мероприятий.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Мероприятия',
  },
  EventInvitationController: {
    businessTitle: 'Приглашения на мероприятия (RSVP)',
    description: 'Отправка индивидуальных и массовых приглашений, подтверждение участия и управление статусами гостей.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Мероприятия',
  },
  EventParticipantController: {
    businessTitle: 'Участники и списки мероприятий',
    description: 'Просмотр и модерация списков участников, экспорт списков гостей и назначение ролей.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Мероприятия',
  },
  InviteRedirectController: {
    businessTitle: 'Обработка ссылок-приглашений',
    description: 'Публичный шлюз для обработки deeplink-приглашений, перенаправление в мобильное приложение или веб.',
    security: 'PUBLIC',
    securityLabel: '🌐 Публичный API',
    securityTone: 'neutral',
    domain: 'Навигация',
  },
  DataExportController: {
    businessTitle: 'Экспорт отчетов и выгрузка данных',
    description: 'Формирование и выгрузка аналитических отчетов по поездкам, сотрудникам и финансам в Excel/PDF.',
    security: 'ADMIN',
    securityLabel: '🛡️ Администратор',
    securityTone: 'warning',
    domain: 'Отчетность',
  },
  FileController: {
    businessTitle: 'Файловое хранилище документов и медиа',
    description: 'Загрузка аватаров, сканов документов, фотоотчетов и получение защищенных URL на скачивание.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Хранилище',
  },
  AuthController: {
    businessTitle: 'Аутентификация и вход пользователей',
    description: 'Авторизация по SMS/Email, выпуск JWT-токенов доступа и обновление клиентских сессий.',
    security: 'PUBLIC',
    securityLabel: '🌐 Публичный API',
    securityTone: 'neutral',
    domain: 'Безопасность',
  },
  DevAuthController: {
    businessTitle: 'Авторизация среды разработки (Dev/Test)',
    description: 'Вспомогательный API для быстрого получения тестовых JWT-токенов в среде разработки.',
    security: 'PUBLIC',
    securityLabel: '🛠️ Dev / Test Only',
    securityTone: 'neutral',
    domain: 'Разработка',
  },
  DevicePushTokenController: {
    businessTitle: 'Регистрация push-токенов мобильных устройств',
    description: 'Регистрация и инвалидация токенов Firebase (FCM) и Apple (APNS) для доставки push-уведомлений.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Уведомления',
  },
  MeController: {
    businessTitle: 'Профиль текущего пользователя',
    description: 'Просмотр и редактирование личных данных, аватара, языка интерфейса и настроек учетной записи.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Пользователь',
  },
  PasswordAuthController: {
    businessTitle: 'Управление паролями учетных записей',
    description: 'Смена пароля, отправка одноразовых кодов сброса и восстановление доступа к аккаунту.',
    security: 'PUBLIC',
    securityLabel: '🌐 Публичный / Сброс пароля',
    securityTone: 'neutral',
    domain: 'Безопасность',
  },
  TelegramCallbackController: {
    businessTitle: 'Интеграция с Telegram-ботом',
    description: 'Прием вебхуков от Telegram API, связывание Telegram ID с учетной записью платформы.',
    security: 'PUBLIC',
    securityLabel: '🌐 Вебхук Telegram',
    securityTone: 'neutral',
    domain: 'Интеграции',
  },
  ImportController: {
    businessTitle: 'Импорт и массовая загрузка данных',
    description: 'Пакетная загрузка списков сотрудников, геоточек и расписаний из файлов Excel и CSV.',
    security: 'ADMIN',
    securityLabel: '🛡️ Администратор',
    securityTone: 'warning',
    domain: 'Импорт',
  },
  IntegrationController: {
    businessTitle: 'Внешние интеграции и вебхуки',
    description: 'Шлюз взаимодействия с корпоративными CRM, ERP-системами и внешними партнерскими сервисами.',
    security: 'ADMIN',
    securityLabel: '🛡️ Администратор',
    securityTone: 'warning',
    domain: 'Интеграции',
  },
  ServiceAccountIntegrationController: {
    businessTitle: 'M2M-интеграция сервисных аккаунтов',
    description: 'Machine-to-machine авторизация по сервисным API-ключам для фоновых микросервисов.',
    security: 'SERVICE',
    securityLabel: '🤖 Сервисный аккаунт (API Key)',
    securityTone: 'warning',
    domain: 'Интеграции',
  },
  ModerationController: {
    businessTitle: 'Модерация контента и жалобы',
    description: 'Проверка жалоб на пользователей, блокировка нежелательного контента и аудит нарушений.',
    security: 'ADMIN',
    securityLabel: '🛡️ Модератор / Админ',
    securityTone: 'negative',
    domain: 'Модерация',
  },
  MonitoringController: {
    businessTitle: 'Мониторинг здоровья и диагностика системы',
    description: 'Проверка доступности базы данных, брокеров сообщений и сбор метрик работоспособности (Healthcheck).',
    security: 'PUBLIC',
    securityLabel: '🌐 Публичный / Мониторинг',
    securityTone: 'neutral',
    domain: 'Инфраструктура',
  },
  EventReminderController: {
    businessTitle: 'Напоминания о событиях и поездках',
    description: 'Управление расписанием отправки push-напоминаний о предстоящих мероприятиях и рейсах.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Уведомления',
  },
  NotificationController: {
    businessTitle: 'Центр входящих уведомлений',
    description: 'Получение ленты уведомлений, отметка о прочтении, подсчет непрочитанных сообщений.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Уведомления',
  },
  MyOrganizationController: {
    businessTitle: 'Моя организация и профиль компании',
    description: 'Настройка параметров текущей организации, реквизитов, корпоративных политик и филиалов.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Организации',
  },
  OrganizationController: {
    businessTitle: 'Реестр организаций и филиалов',
    description: 'Создание, регистрация и администрирование юридических лиц и подразделений в системе.',
    security: 'ADMIN',
    securityLabel: '🛡️ Администратор',
    securityTone: 'warning',
    domain: 'Организации',
  },
  OrganizationMemberController: {
    businessTitle: 'Сотрудники организации и роли доступа',
    description: 'Добавление сотрудников, управление должностями и разграничение прав доступа.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Организации',
  },
  PaymentAvailabilityController: {
    businessTitle: 'Проверка доступности платежных шлюзов',
    description: 'Определение доступных провайдеров оплаты (Kaspi, банковские карты) для региона пользователя.',
    security: 'PUBLIC',
    securityLabel: '🌐 Публичный API',
    securityTone: 'neutral',
    domain: 'Платежи',
  },
  PaymentController: {
    businessTitle: 'Проведение платежей и транзакций',
    description: 'Инициализация безналичных оплат, проверка статуса банковского эквайринга и выдача чеков.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Платежи',
  },
  GeofenceController: {
    businessTitle: 'Геозоны и контроль периметров',
    description: 'Настройка полигонов допустимых зон поездок, парковок и контрольных гео-точек на карте.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Геосервисы',
  },
  PlaceController: {
    businessTitle: 'Справочник мест, адресов и локаций',
    description: 'Каталог точек отправления и прибытия, сохранение корпоративных адресов и геокодирование.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Геосервисы',
  },
  AccountDeletionController: {
    businessTitle: 'Удаление аккаунта (право на забвение)',
    description: 'Запрос на деактивацию и полное удаление персональных данных пользователя из платформы.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Приватность',
  },
  PrivacyController: {
    businessTitle: 'Настройки приватности и согласия',
    description: 'Управление согласиями на обработку ПДН, видимость пользователя в поиске и доступ к геопозиции.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Приватность',
  },
  SafetyController: {
    businessTitle: 'Безопасность поездок и тревожная кнопка SOS',
    description: 'Экстренная связь во время поездки, передача координат доверенным лицам и в службу поддержки.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Безопасность',
  },
  FriendController: {
    businessTitle: 'Список друзей и личные контакты',
    description: 'Управление кругом общения, отправка запросов в друзья для планирования совместных поездок.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Социальное',
  },
  PersonalGroupController: {
    businessTitle: 'Пользовательские группы и круги доверия',
    description: 'Создание и управление закрытыми группами сотрудников или коллег по регулярным маршрутам.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Социальное',
  },
  UserDirectoryController: {
    businessTitle: 'Каталог пользователей и глобальный поиск',
    description: 'Поиск зарегистрированных сотрудников и попутчиков по имени, должности и контактам.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Пользователи',
  },
  SyncController: {
    businessTitle: 'Офлайн-синхронизация клиентских данных',
    description: 'Обмен дельтами изменений между мобильным клиентом и сервером при нестабильной сети.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Синхронизация',
  },
  RoutingController: {
    businessTitle: 'Расчет маршрутов, километража и навигации',
    description: 'Вычисление оптимального автомобильного маршрута через точки посадки, оценка времени и пробок.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Навигация',
  },
  TripController: {
    businessTitle: 'Управление поездками и маршрутами',
    description: 'Создание заказов поездок, назначение водителей, трекинг статуса в реальном времени и завершение рейсов.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Поездки',
  },
  WorkforceAdjustmentController: {
    businessTitle: 'Корректировки рабочих графиков и смен',
    description: 'Учет переносов рабочих смен, замен сотрудников, согласование отгулов и больничных.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Персонал',
  },
  WorkforceController: {
    businessTitle: 'Управление персоналом и графиками смен',
    description: 'Составление расписания персонала, распределение по точкам присутствия и учет рабочих часов.',
    security: 'AUTHENTICATED',
    securityLabel: '🔒 Закрытый API (JWT)',
    securityTone: 'positive',
    domain: 'Персонал',
  },
};

/**
 * Returns rich business metadata, security level, and description for a controller.
 */
export function getControllerMetadata(controllerName: string): ControllerMeta {
  const cleanName = controllerName.replace(/\.java$/, '').trim();

  if (CONTROLLER_REGISTRY[cleanName]) {
    return CONTROLLER_REGISTRY[cleanName];
  }

  // Fallback generation for any custom or new controllers
  const isPublic =
    /Auth|Public|Callback|Health|Ping|Redirect/i.test(cleanName);
  const isAdmin =
    /Admin|Moderation|Manage|Export|Import|Internal/i.test(cleanName);
  const isService =
    /ServiceAccount|System|M2M/i.test(cleanName);

  const words = cleanName
    .replace(/Controller$/, '')
    .replace(/([a-z])([A-Z])/g, '$1 $2');

  const security = isPublic
    ? 'PUBLIC'
    : isService
      ? 'SERVICE'
      : isAdmin
        ? 'ADMIN'
        : 'AUTHENTICATED';

  const securityLabel = isPublic
    ? '🌐 Публичный API'
    : isService
      ? '🤖 Сервисный аккаунт'
      : isAdmin
        ? '🛡️ Администратор'
        : '🔒 Закрытый API (JWT)';

  const securityTone = isPublic
    ? 'neutral'
    : isService || isAdmin
      ? 'warning'
      : 'positive';

  return {
    businessTitle: `Управление: ${words}`,
    description: `Обработка бизнес-операций и сценариев модуля ${words}.`,
    security,
    securityLabel,
    securityTone,
    domain: 'Бизнес-модуль',
  };
}
