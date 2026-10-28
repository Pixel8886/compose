# Task Service API

Учебный REST API для управления задачами. Используются Java 21, PostgreSQL 16 и Docker Compose.

## Запуск

Требуется установленный и запущенный Docker Desktop.

В корневой папке проекта выполнить:

```bash
docker compose up --build
```

После запуска API доступен на `http://localhost:8080`.

## Проверка

Проверка состояния:

```bash
curl.exe http://localhost:8080/health
```

Получить все задачи:

```bash
curl.exe http://localhost:8080/tasks
```

Создать задачу:

```bash
curl.exe -X POST http://localhost:8080/tasks -H "Content-Type: application/json" -d "{\"title\":\"Сделать контрольную точку Compose\",\"description\":\"Подключить PostgreSQL\",\"done\":false}"
```

Изменить задачу (заменить `1` на ID существующей задачи):

```bash
curl.exe -X PUT http://localhost:8080/tasks/1 -H "Content-Type: application/json" -d "{\"title\":\"Контрольная точка выполнена\",\"description\":\"Проверено через Docker Compose\",\"done\":true}"
```

Удалить задачу:

```bash
curl.exe -X DELETE http://localhost:8080/tasks/1
```

Посмотреть состояние контейнеров и логи:

```bash
docker compose ps
docker compose logs
docker compose logs api
docker compose logs db
```

## Проверка сохранения данных

1. Создать задачу через POST.
2. Выполнить `docker compose down`.
3. Снова выполнить `docker compose up --build -d`.
4. Выполнить `curl.exe http://localhost:8080/tasks`.
5. Убедиться, что созданная задача осталась в базе.

Не используйте `docker compose down -v` для этой проверки: флаг `-v` удаляет volume с данными.

## Архитектура

- `api` - Java HTTP API, порт 8080.
- `db` - PostgreSQL 16.
- API подключается к базе по адресу `jdbc:postgresql://db:5432/app`. Имя `db` - имя сервиса Compose.
- `db_data` - именованный volume для постоянного хранения данных PostgreSQL.
- Таблица `tasks` создаётся автоматически при запуске API.

## CRUD endpoints

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/health` | Проверка состояния API |
| GET | `/tasks` | Получить все задачи |
| POST | `/tasks` | Создать задачу |
| PUT | `/tasks/{id}` | Обновить задачу |
| DELETE | `/tasks/{id}` | Удалить задачу |
