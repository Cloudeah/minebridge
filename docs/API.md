# Протокол 1.0

WebSocket `/bridge`, управляющие сообщения JSON. Сначала hello, затем authenticate. `session_id` возвращается hello и меняется при смене мира/игрока/измерения; старые команды нельзя переносить между сессиями.

```json
{
  "protocol_version": "1.0", "session_id": "ID from hello",
  "message_id": "unique delivery ID", "request_id": "unique command ID",
  "actor_id": "UUID from capabilities", "expected_revision": 42,
  "type": "task.submit",
  "data": {
    "action": "move.to", "parameters": {"target": {"x": 10, "y": 64, "z": 20}},
    "priority": 0, "timeout_ticks": 1200, "max_actions": 10000, "depends_on": []
  }
}
```

`expected_revision` опционален; для изменяющих действий обязателен actor_id. Первый hello допускает `session_id: null`; отправьте `data.actor_mode: existing_player`, затем authenticate с `data.token`. Каждое сообщение требует message_id, request_id и объект data. Не журналируйте аутентификационные сообщения.

Ответ: type=response, тот же request_id. Ошибка: type=error, data.code/message/retryable/revision. Длительное действие отвечает status=accepted и task_id; успех определяется терминальным состоянием.

В течение 10 минут (ёмкость 4096) повтор request_id с тем же типом/содержимым возвращает сохранённое подтверждение. Другие параметры дают request_id_conflict. Переполнение окна отклоняет новые запросы. После истечения окна изменяющую команду нельзя повторять без проверки мира.

## Сессия, наблюдение и задачи

| Операция | data |
| --- | --- |
| session.hello | actor_mode: existing_player |
| session.authenticate | token |
| session.capabilities / ping | {} |
| session.resume | after_sequence |
| observe.snapshot | опционально profile: full / compact |
| observe.subscribe | interval_ticks: 4..1200, events: true, опционально profile: full / compact |
| observe.unsubscribe | {} |
| task.submit | action, parameters, priority: -100..100, timeout_ticks: 1..12000, max_actions: 1..100000, depends_on: [task_id], опционально expected_control_epoch и model_goal_id |
| task.status / pause / resume / cancel | task_id |
| task.list / control.stop | {} |
| map.query / memory.query | x,y,z, radius: 1..64, limit: 1..2048, опционально block |
| memory.annotate | text, опционально position |
| vision.capture / subscribe | width: 64..640, height: 64..360, interval_ticks: 4..1200, опционально crop и observation_profile: full / compact |
| vision.unsubscribe | {} |
| model.status | {} — текущая цель, статус и подключение контроллера |
| model.claim | goal_id — принять цель, созданную владельцем в окне F7 |
| model.report | goal_id, state: thinking / executing / completed / waiting / failed, step, detail |

Дополнения версии мода 0.2.0 сохраняют протокол 1.0. Capabilities объявляет `observation_profiles`, `decision_guards` и `model_control`. Профиль `full` по умолчанию сохраняет прежний снимок. `compact` возвращает шесть ближайших наблюдённых блоков, четыре ближайшие сущности, занятые слоты инвентаря и занятые слоты меню с первыми четырьмя пустыми; номера слотов, UUID, свойства блоков и menu revision сохраняются. Пропущенные данные остаются неизвестными.

Снимок содержит `control_epoch`. Контроллер сохраняет его до обращения к модели и передаёт как `task.submit.data.expected_control_epoch`. Мод проверяет значение при принятии задачи; ручной ввод, остановка, повторное разрешение управления или разрыв сессии делают старое решение недействительным (`stale_control`). Обычные игровые тики не меняют этот счётчик. Старые клиенты могут продолжать отправлять задачи без этого поля.

Цель создаётся только локальным владельцем; операции `model.start` по сети нет. Для её действий передаётся `model_goal_id`. Отменённая или сменившаяся цель отклоняет поздние задачи/отчёты с `stale_goal`. Статус `completed` означает сообщение модели о завершении цели; фактические игровые изменения подтверждаются обычными результатами задач.

Зависимости ссылаются на ранее принятые задачи; неуспех зависимости даёт dependency_failed. В каждый момент выполняется одна задача. Пауза освобождает управление; отмена не откатывает мир. После control.stop нужен F8 владельцем.

События содержат event_id, sequence, game_tick, revision. Типы: task.*, actor.health/death, inventory.changed, entity.observed/observation_lost, interface.changed, observe.changed, control.*, model.updated, vision.error. Потеря наблюдения не равна смерти. Критические события здоровья, смерти, задач и новых целей доставляются без обычной подписки.

История — 512 событий. session.resume возвращает history_gap, events, snapshot, tasks, last_sequence, require_plan_revalidation. При разрыве/переполнении обновите снимок и задачи; движение не возобновляется автоматически. task.resume нужен для конкретной paused задачи. Результаты живут в текущей сессии; при лимите 256 задач терминальные записи могут освобождаться.

## Действия

Действия доступны в task.submit или непосредственно в type (со стандартными лимитами):

| Действие | Параметры |
| --- | --- |
| move.to | target: {x,y,z}, tolerance: 0.3..16, sprint |
| move.follow | entity_id, distance: 0.3..16, sprint |
| move.jump | count: целое 1..30, по умолчанию 1; прыжки на месте, мод 0.2.1+ |
| look.at | target или entity_id |
| combat.attack | entity_id, chase, stop_health |
| item.use | hand: main/off, duration_ticks: 1..1200 |
| world.interact / container.open | position или entity_id |
| container.close | {} |
| block.break | position, require_tool: true |
| block.place | position, block: namespace:id, properties: {} |
| inventory.select | slot: 0..8 |
| inventory.transfer / equip | from_slot, to_slot, count: 1..64, menu_id, menu_revision |
| craft.execute | item, count: 1..4096 |
| build.execute | origin, rotation: 0/90/180/270, blocks, replace: false |

route.plan / craft.plan / build.plan / inventory.inspect — непосредственные запросы без задач. route.plan принимает target; остальные планы — параметры соответствующего execute. Слоты относятся к текущему меню, не к индексам инвентаря игрока: используйте context.menu_slots/menu_id/menu_revision. Экипировка — перенос в разрешённый armor/offhand слот. Занятый курсор/недостаточная ёмкость отклоняют перенос.

XYZ — мировые координаты в блоках в текущем измерении. Блоки и чертежи целочисленные. Маршрут ограничен 96 блоками и 8192 ячейками; жидкости, опасности, неподдержанные коллизии и неизвестные чанки исключаются.

`move.jump` выполняет короткие нажатия клавиши прыжка и считает только пары «отрыв выше исходной высоты → приземление», подтверждённые интегрированным сервером. Запуск требует земли под игроком, отсутствия полёта, воды/лавы и транспорта. В снимке `actor` доступны `on_ground`, `flying`, `in_water`, `in_lava`, `riding`. Прогресс/результат содержит `requested`, `completed`, `jump_requests`, `stage`, `source: integrated_server`; `confirmed_changes.jumps` и `actions` отражают число подтверждённых прыжков. До подтверждения может быть только один запрос прыжка. Недостаточный бюджет действий останавливает серию перед следующим нажатием; точный бюджет позволяет дождаться последнего приземления. Отсутствие подтверждения отрыва более 20 тиков даёт `jump_unconfirmed`, неподходящий режим игрока — `jump_unavailable`. Лимит времени, разрешённые области, ручное вмешательство и F9 действуют как для других задач.

Чертёж:

```json
{
  "origin": {"x":0,"y":64,"z":0}, "rotation":90,
  "blocks": [
    {"x":0,"y":0,"z":0,"type":"minecraft:stone"},
    {"x":1,"y":0,"z":0,"type":"minecraft:oak_log","properties":{"axis":"y"}}
  ]
}
```

Позиции относительны origin; поворачиваются координаты, facing и ось X/Z. План не меняет мир. Исполнитель проверяет реальные блоки/опоры/материал; итоговый успех требует проверки всего чертежа сервером.

## Зрение MBV1

```
4 bytes "MBV1"
uint32 big-endian: размер JSON
UTF-8 JSON metadata
rgb.bytes: PNG
depth.bytes: float32 little-endian
```

Порядок пикселей: слева направо, сверху вниз. Глубина — расстояние вдоль оси камеры в блоках; фон/невалидные значения — NaN. Проекция column-major. crop задан в исходных пикселях framebuffer от верхнего левого угла: x,y,width,height.

Метаданные: frame_id, observation_id, actor_id, dimension, capture_time, game_tick, world_revision, camera, rgb, depth, objects, observation, sync_quality, age_ms_at_enqueue, limitations, request_id. RGB/depth — один framebuffer; геометрия сущностей относится к тику, рендер интерполирован. Это явно обозначено. Прозрачность, частицы, дрожание камеры, шейдеры/постэффекты могут нарушать выравнивание.

Прямоугольник сущности — не маска. Включение в objects требует луча и согласованности глубины у центра; visible_fraction=unknown. Блоки имеют пиксель поверхности и грань. Нативный захват поддерживает OpenGL и исходный framebuffer ≤8 мегапикселей.

В потоке сохраняется последний кадр; команды/результаты передаются раньше изображения. Переполнение управляющей очереди закрывает соединение с причиной и требует resume. Входное сообщение ограничено 64 KiB.
