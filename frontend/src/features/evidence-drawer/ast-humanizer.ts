// Formatter for AST condition trees from Java/analyzer into readable expressions and Russian summaries

interface AstNode {
  type?: string;
  name?: string;
  value?: unknown;
  operator?: string;
  left?: AstNode;
  right?: AstNode;
  operand?: AstNode;
  receiver?: AstNode;
  method?: string;
  arguments?: AstNode[];
  field?: string;
  [key: string]: unknown;
}

const OP_MAP: Record<string, string> = {
  EQUAL: '==',
  NOT_EQUAL: '!=',
  OR: '||',
  AND: '&&',
  GREATER: '>',
  GREATER_OR_EQUAL: '>=',
  LESS: '<',
  LESS_OR_EQUAL: '<=',
  PLUS: '+',
  MINUS: '-',
  MULTIPLY: '*',
  DIVIDE: '/',
  MODULO: '%',
  ASSIGN: '=',
};

function formatNode(node: unknown): string {
  if (node === null || node === undefined) return 'null';
  if (typeof node === 'string') return `"${node}"`;
  if (typeof node === 'number' || typeof node === 'boolean') return String(node);
  if (Array.isArray(node)) return node.map(formatNode).join(', ');

  if (typeof node !== 'object') return String(node);

  const n = node as AstNode;
  const type = n.type?.toUpperCase();

  switch (type) {
    case 'BINARY': {
      const op = OP_MAP[n.operator || ''] || n.operator || '&&';
      const leftStr = formatNode(n.left);
      const rightStr = formatNode(n.right);
      return `${leftStr} ${op} ${rightStr}`;
    }

    case 'UNARY': {
      const rawOp = n.operator || '';
      const op = rawOp === 'NOT' ? '!' : (OP_MAP[rawOp] || rawOp);
      const operandStr = formatNode(n.operand);
      return `${op}(${operandStr})`;
    }

    case 'CALL': {
      const args = Array.isArray(n.arguments) ? n.arguments.map(formatNode).join(', ') : '';
      const method = n.method || 'call';
      if (n.receiver) {
        const receiverStr = formatNode(n.receiver);
        return `${receiverStr}.${method}(${args})`;
      }
      return `${method}(${args})`;
    }

    case 'REFERENCE':
    case 'IDENTIFIER':
      return n.name || (n.value !== undefined ? String(n.value) : 'var');

    case 'LITERAL':
      if (n.value === null) return 'null';
      if (typeof n.value === 'string') return `"${n.value}"`;
      return String(n.value ?? 'null');

    case 'MEMBER_SELECT':
    case 'FIELD': {
      const field = n.field || n.name || '';
      if (n.receiver) {
        return `${formatNode(n.receiver)}.${field}`;
      }
      return field;
    }

    default:
      if (n.name) return n.name;
      if (n.value !== undefined) return formatNode(n.value);
      if (n.operator && n.left && n.right) {
        const op = OP_MAP[n.operator] || n.operator;
        return `${formatNode(n.left)} ${op} ${formatNode(n.right)}`;
      }
      return JSON.stringify(node);
  }
}

/**
 * Formats a raw AST condition string (or object) into a human-readable expression.
 */
export function formatAstCondition(raw: string | unknown): string {
  if (!raw) return '';
  if (typeof raw !== 'string') {
    return formatNode(raw);
  }

  const trimmed = raw.trim();
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
    return raw;
  }

  try {
    const parsed = JSON.parse(trimmed);
    return formatNode(parsed);
  } catch {
    return raw;
  }
}

/**
 * Explains common business conditions in Russian.
 */
export function explainConditionInRussian(conditionExpr: string): string | null {
  const lower = conditionExpr.toLowerCase();

  if (lower.includes('response') && (lower.includes('null') || lower.includes('empty') || lower.includes('ok'))) {
    return 'Проверка корректности ответа внешнего геосервиса (маршрут найден и валиден)';
  }
  if (lower.includes('enabled')) {
    return 'Проверка статуса активности функции (флаг enabled)';
  }
  if (lower.includes('transporttype')) {
    return 'Валидация выбранного типа транспорта';
  }
  if (lower.includes('status') && lower.includes('active')) {
    return 'Проверка активности статуса сущности';
  }
  if (lower.includes('role') || lower.includes('auth') || lower.includes('permission')) {
    return 'Проверка прав доступа и роли пользователя';
  }
  if (lower.includes('user') && lower.includes('null')) {
    return 'Проверка наличия профиля пользователя в сессии';
  }

  return null;
}

/**
 * Cleans a multi-line description that might contain embedded raw AST JSON.
 */
export function humanizeDescription(text: string): string {
  if (!text) return '';

  return text
    .split('\n')
    .map((line) => {
      const match = line.match(/^([^:]+):\s*(\{.*\}|\[.*\])\s*$/);
      if (match) {
        const [, label, json] = match;
        const formatted = formatAstCondition(json);
        const explanation = explainConditionInRussian(formatted);
        if (explanation) {
          return `${label}: ${explanation} (${formatted})`;
        }
        return `${label}: ${formatted}`;
      }
      return line;
    })
    .join('\n');
}

/**
 * Translates technical Java/Spring repository and service methods into plain Russian business actions.
 */
export function humanizeMethodName(name: string): { title: string; detail: string } {
  if (!name) return { title: 'Выполнение метода', detail: '' };

  const clean = name.replace(/^.*[#.]/, '');

  if (clean.includes('findByPrincipalIdAndResourceTypeAndResourceId')) {
    return {
      title: 'Проверка прав доступа к камере',
      detail: 'Запрос в БД: проверка наличия у пользователя активного разрешения на ресурс камеры',
    };
  }
  if (clean.includes('findByPrincipalId')) {
    return {
      title: 'Поиск полномочий пользователя',
      detail: 'Чтение прав и ролей пользователя из базы данных',
    };
  }
  if (clean.includes('streamResource')) {
    return {
      title: 'Инициализация трансляции видеопотока',
      detail: 'Запуск потоковой передачи видеосигнала камеры через HLS/RTSP',
    };
  }
  if (clean.includes('playback')) {
    return {
      title: 'Воспроизведение видеоархива',
      detail: 'Чтение и покадровая выдача архивных записей за выбранный период',
    };
  }
  if (clean.includes('map') || clean.includes('getCamerasMap')) {
    return {
      title: 'Отображение камер на карте',
      detail: 'Выгрузка геопозиций и текущих статусов активности камер',
    };
  }
  if (/^findById/i.test(clean)) {
    return {
      title: 'Поиск записи по ID в БД',
      detail: 'Чтение единичной записи из таблицы по уникальному идентификатору',
    };
  }
  if (/^delete/i.test(clean)) {
    return {
      title: 'Удаление записи из БД',
      detail: 'Безвозвратное удаление объекта из базы данных',
    };
  }
  if (/^save|^persist/i.test(clean)) {
    return {
      title: 'Сохранение изменений в БД',
      detail: 'Фиксация созданной или измененной сущности в хранилище данных',
    };
  }
  if (/^findAll|^list/i.test(clean)) {
    return {
      title: 'Получение реестра записей',
      detail: 'Выгрузка полного списка элементов из базы данных',
    };
  }

  // Split camelCase into readable words
  const words = clean
    .replace(/([A-Z])/g, ' $1')
    .toLowerCase()
    .trim();

  return {
    title: `Операция: ${clean}`,
    detail: `Вызов метода бизнес-логики (${words})`,
  };
}

/**
 * Translates Java throw statements and exceptions into clear business failure descriptions.
 */
export function humanizeException(raw: string): { title: string; detail: string } {
  if (!raw) return { title: 'Системная ошибка', detail: '' };

  if (raw.includes('CameraPlaybackException')) {
    return {
      title: 'Сбой видеотрансляции / камеры',
      detail:
        'Видеопоток с камеры прерван или устройство оффлайн. Запрос трансляции остановлен с возвратом ошибки клиенту.',
    };
  }
  if (raw.includes('AccessDenied') || raw.includes('SecurityException')) {
    return {
      title: 'Отказ в доступе (403 Forbidden)',
      detail: 'Пользователь не обладает правами для просмотра или модификации этого ресурса.',
    };
  }
  if (raw.includes('Authentication') || raw.includes('Unauthorized')) {
    return {
      title: 'Пользователь не авторизован (401)',
      detail: 'Отсутствует или просрочен токен аутентификации.',
    };
  }
  if (raw.includes('EntityNotFound') || raw.includes('NotFound')) {
    return {
      title: 'Объект не найден (404 Not Found)',
      detail: 'Запрашиваемая запись отсутствует в базе данных системы.',
    };
  }
  if (raw.includes('Validation') || raw.includes('IllegalArgument')) {
    return {
      title: 'Некорректные параметры (400 Bad Request)',
      detail: 'Переданные входные данные не прошли проверку бизнес-правил.',
    };
  }

  const messageMatch = raw.match(/"([^"]+)"/);
  const msg = messageMatch ? messageMatch[1] : '';

  return {
    title: 'Исключительная ситуация',
    detail: msg ? `Сообщение системы: "${msg}"` : raw,
  };
}

/**
 * Translates any ScenarioMember into an executive, non-technical business step card.
 */
export function humanizeScenarioStep(
  kind: string,
  label: string,
  subtitle?: string | null,
): {
  businessTitle: string;
  businessDetail: string;
  category: 'auth' | 'db' | 'business' | 'error' | 'net';
  categoryLabel: string;
} {
  if (kind === 'EXCEPTION' || label.startsWith('throw ') || label.includes('Exception')) {
    const ex = humanizeException(label);
    return {
      businessTitle: ex.title,
      businessDetail: ex.detail,
      category: 'error',
      categoryLabel: 'Ошибка / Сбой',
    };
  }

  if (kind === 'DATABASE_READ' || kind === 'DATABASE_WRITE' || label.includes('find') || label.includes('save')) {
    const m = humanizeMethodName(label);
    const isAuth = label.includes('Principal') || label.includes('Permission') || label.includes('Role');
    return {
      businessTitle: m.title,
      businessDetail: m.detail,
      category: isAuth ? 'auth' : 'db',
      categoryLabel: isAuth ? 'Авторизация и права' : (kind === 'DATABASE_WRITE' ? 'Запись в БД' : 'Чтение из БД'),
    };
  }

  if (kind === 'ENDPOINT' || kind === 'HTTP_REQUEST') {
    return {
      businessTitle: `Входящий запрос: ${label}`,
      businessDetail: subtitle || 'Инициация бизнес-процесса от клиента/приложения',
      category: 'net',
      categoryLabel: 'Входная точка API',
    };
  }

  if (kind === 'TRANSACTION') {
    return {
      businessTitle: 'Транзакционный блок данных',
      businessDetail: 'Гарантия атомарности: все изменения данных фиксируются вместе или откатываются',
      category: 'db',
      categoryLabel: 'Транзакция',
    };
  }

  const m = humanizeMethodName(label);
  return {
    businessTitle: m.title,
    businessDetail: m.detail || subtitle || '',
    category: 'business',
    categoryLabel: 'Бизнес-логика',
  };
}

