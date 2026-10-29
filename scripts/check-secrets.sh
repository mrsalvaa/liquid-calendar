#!/usr/bin/env bash
# Busca datos que no deben llegar al repo público: dominios propios, rutas de usuario, claves
# privadas, contraseñas y hashes. Sin argumentos revisa lo que está en el índice (lo que se va a
# commitear); con --all, todos los archivos versionados; con --history, cada versión de cada
# archivo en todos los commits y los mensajes de commit.
#
# Patrones personales (dominio, usuario, email...): uno por línea, como expresión regular
# extendida, en .secret-patterns (ignorado por git). Una línea con "secret-check: ignore" se salta.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

patterns=(
    '[A-Za-z0-9-]+\.duckdns\.org'                                # subdominio real (los ejemplos usan example.org)
    '/home/[a-z_][a-z0-9_-]*/'                                   # rutas personales
    '/Users/[A-Za-z0-9_-]+/'
    '-----BEGIN [A-Z ]*PRIVATE KEY-----'
    '\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}'                     # hashes bcrypt (htpasswd)
    # Contraseñas como literal entre comillas, o como clave=valor de un .properties/.env
    '(password|passwd|secret|token)["'"'"']?[[:space:]]*[=:][[:space:]]*["'"'"'][^"'"'"'[:space:]<>]{6,}["'"'"']'
    'Authorization:[[:space:]]*(Basic|Bearer)[[:space:]]+[A-Za-z0-9+/=._-]{8,}'
)
# clave=valor de archivos de configuración (en el código, "password = campo" es normal).
kv_pattern='^[[:space:]]*[A-Za-z_.-]*([Pp]assword|PASSWORD|[Ss]ecret|SECRET|[Tt]oken|TOKEN)[[:space:]]*[=:][[:space:]]*[^[:space:]<>]{6,}[[:space:]]*$'
if [[ -f .secret-patterns ]]; then
    while IFS= read -r line; do
        [[ -n "$line" && "$line" != \#* ]] && patterns+=("$line")
    done < .secret-patterns
fi

# Cada elemento de "files" es "objeto<TAB>ruta"; show() imprime el contenido de ese objeto.
case "${1:-}" in
    --all)
        mapfile -t files < <(git ls-files -s | awk -F'\t' '{split($1, a, " "); print a[2] "\t" $2}')
        ;;
    --history)
        # Todos los contenidos distintos que tuvo cada archivo en cualquier commit.
        mapfile -t files < <(git rev-list --objects --all | git cat-file --batch-check='%(objecttype) %(objectname) %(rest)' |
            awk '$1 == "blob" && $3 != "" {print $2 "\t" $3}' | sort -u)
        ;;
    *)
        mapfile -t files < <(git diff --cached --name-only --diff-filter=ACMR | while read -r f; do printf '%s\t%s\n' "$(git rev-parse ":$f")" "$f"; done)
        ;;
esac
show() { git cat-file -p "${1%%$'\t'*}"; }

found=0
for entry in "${files[@]}"; do
    f="${entry#*$'\t'}"
    [[ "$f" == "scripts/check-secrets.sh" ]] && continue
    # Solo texto: los binarios (jar del wrapper, imágenes) se saltan.
    show "$entry" | grep -Iq . 2>/dev/null || continue
    file_patterns=("${patterns[@]}")
    [[ "$f" =~ \.(kt|kts|java|xml)$ ]] || file_patterns+=("$kv_pattern")
    for p in "${file_patterns[@]}"; do
        if hits=$(show "$entry" | grep -nE -e "$p" | grep -v 'secret-check: ignore'); then
            # Solo el número de línea: no se repite el secreto en la terminal.
            where="$f"
            if [[ "${1:-}" == "--history" ]]; then
                where="$f (en $(git log --all --format=%h --find-object="${entry%%$'\t'*}" | paste -sd, -))"
            fi
            echo "Posible secreto en $where, líneas: $(cut -d: -f1 <<<"$hits" | paste -sd, -)"
            found=1
        fi
    done
done

if [[ "${1:-}" == "--history" ]]; then
    # Los mensajes de commit también son públicos.
    for c in $(git rev-list --all); do
        for p in "${patterns[@]}"; do
            if git log -1 --format=%B "$c" | grep -qE -e "$p"; then
                echo "Posible secreto en el mensaje del commit $(git rev-parse --short "$c")"
                found=1
            fi
        done
    done
    echo "Commits revisados: $(git rev-list --all | wc -l)"
fi

if (( found )); then
    echo "Revisá esas líneas antes de commitear (o marcalas con 'secret-check: ignore' si son de prueba)."
    exit 1
fi
echo "Sin secretos a la vista (${#files[@]} archivos revisados)."
