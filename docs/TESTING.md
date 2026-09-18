# Сборка и тестирование

## Локальная сборка

Нужны Git, JDK 17/21 и доступ к Maven Central, Spigot snapshots и Gradle distributions. Wrapper включён в репозиторий, отдельный Gradle не нужен.

```sh
./gradlew clean build
```

PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat clean build
```

Плагин: build/libs/ServerBootstrap-2.1.0.jar. Offline-дистрибутив: build/distributions/ServerBootstrap-2.1.0-offline.zip. HTML-отчёт: build/reports/tests/test/index.html; JUnit XML: build/test-results/test/.

Gradle 8.8 запускайте на Java 17/21. Java 25 используется как отдельный процесс тестов:

```powershell
.\gradlew.bat test "-PtestApi=26.3-R0.1-SNAPSHOT" "-PtestJavaHome=C:\Program Files\Java\jdk-25"
```

Полная матрица Windows:

```powershell
.\scripts\test-matrix.ps1 -Java17 "C:\Java\jdk-17" -Java21 "C:\Java\jdk-21" -Java25 "C:\Java\jdk-25"
```

Результаты: build/compatibility/results.json и отдельные XML для каждой версии. Linux CI описан в .github/workflows/build.yml. Snapshot API могут меняться upstream; фактическую совместимость новой сборки подтверждает повторный прогон.

Тесты работают только во временных каталогах. Для HTTPS генерируется одноразовый сертификат keytool, доверенный только отдельным тестовым клиентом; production TLS-проверка не отключается. На Windows проверка безопасных путей использует junction, если ОС не разрешает создание symlink.

## Сценарии автоматических тестов

- Схема конфигурации, неверные поля/типы, обязательные файлы и скрытие секретов.
- Legacy GitHub/GitLab, кодирование project/ref, релизы, HTTPS и Google Drive direct-адаптер.
- Верная/неверная SHA-256, HTML/повреждённый ZIP, CRC, traversal, Windows-пути, case/Unicode-конфликты, лимиты.
- Реальный локальный TLS, статусы HTTP, соединение без сервера, ошибка доверия TLS, общий таймаут, застывшее тело, лимит потока.
- Редиректы: сохранение auth на origin, удаление при смене origin, запрет HTTP и циклов.
- Ошибка до применения, ошибка посередине, ошибка записи состояния, полный и неполный откат, прерывание процесса и повторное восстановление.
- Maintenance: две загрузки, отказ при игнорировании level-name/загруженном мире, проверка staging SHA, отмена, повтор, полная замена каталогов и откат после каждой стадии.
- Проверка из отдельного JVM-процесса, что probe session.lock не снимает блокировку сервера.
- Глобальная блокировка установки/check, межпроцессный node.lock, сохранение версии.
- HTTP method/path/auth/body/status/conflict и отсутствие секретов в журнале.
- Permission для консоли/администратора, минимальный API и байткод Java 17.

## Полностью изолированный HTTPS-стенд

Вместо настоящего Minecraft-сервера создаётся новый каталог со служебным config.yml. Никаких исполняемых сторонних плагинов, рабочих миров и production-файлов там нет. Скрипт отказывается использовать существующий немаркированный каталог.

1. Создайте стенд (Python 3 и JDK 17+):
   ```powershell
   python scripts/create-sandbox.py C:\Temp\sb-demo --java-home "C:\Program Files\Java\jdk-21"
   ```
2. В отдельном терминале запустите локальный HTTPS:
   ```powershell
   & "C:\Program Files\Java\jdk-21\bin\java.exe" scripts/SandboxServer.java C:\Temp\sb-demo 18443
   ```
3. Распакуйте offline ZIP в C:\Temp\sb-tool и выполните:
   ```powershell
   & "C:\Program Files\Java\jdk-21\bin\java.exe" "-Djavax.net.ssl.trustStore=C:\Temp\sb-demo\sandbox.p12" "-Djavax.net.ssl.trustStorePassword=sandbox-only" -cp "C:\Temp\sb-tool\lib\*" dev.kekaop.ServerBootstrap.OfflineMain C:\Temp\sb-demo\server demo check --server-stopped
   ```
4. Замените check на install. Должен появиться server/plugins/Demo/config.yml с текстом Sandbox v1.
5. Подготовьте обновление:
   ```powershell
   python scripts/create-sandbox.py C:\Temp\sb-demo --java-home "C:\Program Files\Java\jdk-21" --version v2 --update
   ```
6. Повторите install: содержимое станет Sandbox v2, а installed.properties сохранит v2 и SHA.
7. В тестовом config.yml замените SHA на 64 нуля. Повторная install должна вернуть код 1, SHA-256 mismatch и сохранить v2.
8. Нажмите Enter в терминале HTTPS-сервера для остановки.

sandbox-only — публичный пароль исключительно одноразового тестового хранилища. Не используйте его в production. Truststore передаётся лишь этому запуску Java; системное доверие не меняется. Для Linux аналогичны python3/java и пути вашего каталога.

## Пробный запуск настоящего Paper

Проводите отдельно для версий из COMPATIBILITY.md. Создайте новый каталог, скачайте нужное ядро через официальный Paper Downloads Service, проверьте SHA скачанного файла. Запускайте правильной Java. При необходимости самостоятельно ознакомьтесь с Minecraft EULA и примите её в тестовом каталоге; эти автоматические тесты её не принимают.

Поместите JAR в plugins, запускайте `java -Xms512M -Xmx2G -jar paper.jar --nogui`. После создания конфигурации остановите сервер, добавьте безопасный тестовый профиль с restart=false, снова запустите и выполните help/check/install/current/status. Затем проверьте повторный запуск с сохранением версии и отказ обычному игроку без permission. Для direct с restart=true настройте отдельный тестовый restart-script. Для maintenance restart-script не требуется: используйте процедуру из MAINTENANCE.md, проверяя остановки, сохранённые фазы и отсутствие старых чанков после возврата.

Рабочие серверы и миры не используются. Старый Gradle runServer удалён: он жёстко привязывал стенд к одной версии и не обеспечивал требуемую матрицу.
