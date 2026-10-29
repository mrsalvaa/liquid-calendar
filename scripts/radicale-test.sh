#!/usr/bin/env bash
# Radicale local para las pruebas de integración, con uv y sin root. Escucha solo en 127.0.0.1.
#   scripts/radicale-test.sh start   arranca en segundo plano (datos en test/radicale/data)
#   scripts/radicale-test.sh stop    lo detiene
#   scripts/radicale-test.sh reset   lo detiene y borra los datos
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

RADICALE_VERSION=3.8.3
BCRYPT_VERSION=5.0.0
DIR="$PWD/test/radicale"
DATA="$DIR/data"
PID="$DATA/radicale.pid"

stop() {
    if [[ -f "$PID" ]] && kill -0 "$(cat "$PID")" 2>/dev/null; then
        kill "$(cat "$PID")"
        echo "Radicale detenido."
    fi
    rm -f "$PID"
}

case "${1:-start}" in
    start)
        stop
        mkdir -p "$DATA"
        chmod 700 "$DATA"
        uv run --quiet --no-project --with "bcrypt==$BCRYPT_VERSION" python -I "$DIR/make-users.py" "$DATA/users"
        sed -e "s#/data#$DATA#g" -e "s#0.0.0.0:5232#127.0.0.1:5232#" "$DIR/config" > "$DATA/config"
        uvx --quiet --from "radicale==$RADICALE_VERSION" --with "bcrypt==$BCRYPT_VERSION" \
            radicale --config "$DATA/config" > "$DATA/radicale.log" 2>&1 &
        echo $! > "$PID"
        for _ in $(seq 1 50); do
            if curl -s -o /dev/null http://127.0.0.1:5232/; then
                echo "Radicale $RADICALE_VERSION en http://127.0.0.1:5232/ (usuario de prueba: test)"
                exit 0
            fi
            sleep 0.2
        done
        echo "Radicale no arrancó; ver $DATA/radicale.log" >&2
        exit 1
        ;;
    stop) stop ;;
    reset)
        stop
        rm -rf "$DATA"
        ;;
    *) echo "Uso: $0 start|stop|reset" >&2; exit 2 ;;
esac
