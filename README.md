# private-isu Kotlin 実装

[private-isu](https://github.com/catatsuy/private-isu) に Kotlin 実装を追加するためのリポジトリです。
Ktor + JDBI + FreeMarker で、Go リファレンス実装（`webapp/golang/app.go`）を移植しています。
現状、Docker Compose と `./gradlew run` でのローカル起動に対応しています。

## このリポジトリについて

このリポジトリは upstream フォーク [Msksgm/private-isu](https://github.com/Msksgm/private-isu) の `kotlin-impl` ブランチと CI で同期される予定です。同期の仕組みが整えば、`Msksgm/private-isu` を clone して `git switch kotlin-impl` するだけで Kotlin 実装を試せるようになります。

ただし現時点では同期メカニズムは未構築（WIP）のため、下記 `Using` の手順に従って本リポジトリを `webapp/kotlin` 配下に `git clone` してください。

## Using

Kotlin で起動するためには以下の手順が必要です。

1. private-isu の [README.md](https://github.com/catatsuy/private-isu/blob/master/README.md#docker-compose) に従って、MySQL に初期データを import する。
2. private-isu の `webapp` に本リポジトリを追加する。
    ```sh
    cd webapp
    git clone https://github.com/Msksgm/private-isu-kotlin.git kotlin
    ```
    > **NOTE:** 将来的には [Msksgm/private-isu](https://github.com/Msksgm/private-isu) の `kotlin-impl` ブランチに切り替えるだけで済む予定です（CI による同期は WIP）。
3. `webapp/docker-compose.yml` の `app.build` を `kotlin` に変更する。
4. 起動する。
    ```sh
    cd webapp
    docker compose up
    ```
5. (Option) ローカルで起動する場合は以下を実行する。MySQL と Memcached への接続先は環境変数で渡す（カッコ内はデフォルト値）。
    ```sh
    export ISUCONP_DB_HOST=localhost   # default: localhost
    export ISUCONP_DB_PORT=3306        # default: 3306
    export ISUCONP_DB_NAME=isuconp     # default: isuconp
    export ISUCONP_DB_USER=root        # default: root
    export ISUCONP_DB_PASSWORD=root    # default: root
    export ISUCONP_MEMCACHED_ADDRESS=localhost  # default: localhost

    ./gradlew run
    ```
    JDK は Gradle Toolchain で JDK 21 が自動解決されます。起動に成功すると以下のように表示されます。
    ```
    2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
    2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
    ```

## Bench

ローカルで benchmarker を走らせる手順です。CI（`.github/workflows/bench.yml`）と同等のフローを手元で再現できます。

### 必要なもの

- Docker（compose v2）と `unzip`, `jq`
- 数 GB の空き容量（`img.zip` が数百 MB、展開後はさらに使う）

### 手順

1. seed データ（`dump.sql.bz2`）を取得する。MySQL の `/docker-entrypoint-initdb.d` が起動時に自動展開して投入する。
    ```sh
    mkdir -p sql
    curl -L --fail -o sql/dump.sql.bz2 \
      https://github.com/catatsuy/private-isu/releases/download/img/dump.sql.bz2
    ```

2. アプリ stack（nginx + app + mysql + memcached）を起動する。
    ```sh
    docker compose up --build -d
    ```

    MySQL の seed 投入完了は `docker compose logs mysql` で確認できる。`http://localhost/` にアクセスして 200 が返れば準備完了。

3. upstream から benchmarker のソースと userdata（bench 用画像）を取得し、image を build する。`userdata/img.zip` の取得・展開を忘れずに（しないと `panic: invalid argument to IntN` になる）。
    ```sh
    git clone --depth=1 https://github.com/catatsuy/private-isu /tmp/upstream
    cd /tmp/upstream/benchmarker/userdata
    curl -L --fail -O \
      https://github.com/catatsuy/private-isu/releases/download/img/img.zip
    unzip -qq -o img.zip
    cd -
    docker build -t private-isu-benchmarker /tmp/upstream/benchmarker
    ```

4. compose ネットワークに join して bench を実行する。
    ```sh
    NET=$(docker network ls --format '{{.Name}}' | grep private-isu-kotlin)
    docker run --network "$NET" -i private-isu-benchmarker \
      /bin/benchmarker -t http://nginx -u /opt/userdata \
      | tee benchmark_output.json
    ```

5. 結果を確認する。`pass: true` であれば bench を通過。
    ```sh
    jq . benchmark_output.json
    # {"pass":true,"score":1738,"success":1652,"fail":1,"messages":[...]}
    ```

### 後片付け

```sh
docker compose down -v
```
