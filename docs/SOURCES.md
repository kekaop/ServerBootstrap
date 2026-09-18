# Источники артефактов

Все адаптеры реализуют `ArtifactSource` и возвращают адрес и параметры авторизации. Общие `ArtifactDownloader`, `ArchiveVerifier` и `TransactionInstaller` отвечают за загрузку, проверку и установку.

Только HTTPS с проверкой сертификата и имени узла. До пяти редиректов; переход на HTTP запрещён. Заголовок авторизации остаётся только на исходном origin (scheme + host + port). После перехода на другой origin токен не возвращается даже при обратном редиректе. Cookies, формы входа и интерактивные подтверждения не поддерживаются.

## GitHub

Репозиторий:

```yaml
source:
  type: github
  mode: repository
  url: https://github.com/YOUR_ORG/server-lobby
  ref: "v1.0.0"
  # auth:
  #   token-env: GITHUB_TOKEN
```

Формируется `https://api.github.com/repos/OWNER/REPO/zipball/REF`. Поддерживается суффикс `.git`. Для Enterprise база по умолчанию `https://HOST/api/v3`; можно задать `api-base`.

Release asset:

```yaml
source:
  type: github
  mode: release
  url: https://github.com/YOUR_ORG/server-lobby/releases/download/v1.0.0/lobby.zip
```

Для приватного asset можно указать адрес `https://api.github.com/repos/OWNER/REPO/releases/assets/ASSET_ID` и token-env. Загрузчик отправляет `Accept: application/octet-stream`. Обычная HTML-страница релиза не является артефактом; автоматического поиска asset по имени нет.

Для private repository токен должен иметь read-доступ к содержимому. Доступ публичного репозитория не требует токена; API-лимиты GitHub всё равно действуют.

Основание: [архив репозитория](https://docs.github.com/en/rest/repos/contents#download-a-repository-archive-zip), [загрузка release asset](https://docs.github.com/en/rest/releases/assets#get-a-release-asset).

## GitLab

```yaml
source:
  type: gitlab
  mode: repository
  url: https://gitlab.com/YOUR_GROUP/subgroup/server-lobby
  ref: "v1.0.0"
  # auth:
  #   token-env: GITLAB_TOKEN
```

Запрос: `https://HOST/api/v4/projects/URL_ENCODED_PROJECT/repository/archive.zip?sha=REF`. Подгруппы кодируются в project ID. Для инсталляций под подпутём задайте api-base явно; source.url представляет путь проекта относительно этой API-базы.

По умолчанию используется заголовок PRIVATE-TOKEN без префикса. Для CI JOB-TOKEN задайте header: JOB-TOKEN и scheme: "". Доступность конкретного API для job token зависит от настройки и версии GitLab; проверьте `check`.

Прямой asset релиза:

```yaml
source:
  type: gitlab
  mode: release
  url: https://gitlab.com/YOUR_GROUP/server-lobby/-/releases/v1.0.0/downloads/lobby.zip
```

Ссылка должна быть настроена как прямой release asset и приводить к ZIP. HTML-страницы релиза и endpoint метаданных не поддерживаются.

Основание: [GitLab archive API](https://docs.gitlab.com/api/repositories/#get-file-archive), [permanent links to release assets](https://docs.gitlab.com/user/project/releases/release_fields/#permanent-links-to-release-assets).

## HTTPS

```yaml
source:
  type: https
  url: https://downloads.example.org/lobby-v1.zip
```

Поддерживаются прямые ZIP с HTTP 200, в том числе signed URLs. Заголовок Content-Type не считается доказательством формата: архив проверяется фактически. HTML, JSON ошибки, gzip content encoding и частичные HTTP 206-ответы не принимаются. Файл не обязан иметь расширение .zip в URL.

## Google Drive

```yaml
source:
  type: google-drive
  url: "https://drive.google.com/uc?export=download&id=YOUR_FILE_ID"
```

Другой допустимый вариант — прямая HTTPS-ссылка вида `https://drive.usercontent.google.com/download?id=YOUR_FILE_ID&export=download`, если она действительно отдаёт ZIP публичному клиенту.

Тип google-drive использует общий HTTPS-загрузчик. Ссылки `/file/d/ID/view` не преобразуются и не считаются download-ссылками. Доступ «всем по ссылке» сам по себе не гарантирует загрузку: квоты, проверка больших файлов, страницы подтверждения и правила сервиса могут вернуть HTML или ошибку. Плагин в таком случае останавливается до применения. Обход авторизации, антибот-проверок, квот и страниц подтверждения не выполняется. Для предсказуемого production-обновления используйте артефакт, доступный без интерактивного сеанса.

## Расширение

Добавьте адаптер ArtifactSource и тип в валидацию конфигурации. Не переносите в адаптер установку, распаковку или проверку SHA. В unit-тестах следует проверить формирование адреса, заголовки и прохождение через общую цепочку. Новые способы авторизации требуют отдельного анализа передачи секретов при редиректах.
