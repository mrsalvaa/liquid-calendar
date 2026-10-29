#!/usr/bin/env bash
# Busca datos que no deben llegar al repo público: dominios propios, rutas de usuario, claves
# privadas, contraseñas y hashes. Sin argumentos revisa lo que está en el índice (lo que se va a
# commitear); con --all revisa todos los archivos versionados.
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

if [[ "${1:-}" == "--all" ]]; then
    mapfile -t files < <(git ls-files)
    show() { cat -- "$1"; }
else
    mapfile -t files < <(git diff --cached --name-only --diff-filter=ACMR)
    show() { git show ":$1"; }
fi

found=0
for f in "${files[@]}"; do
    [[ "$f" == "scripts/check-secrets.sh" ]] && continue
    # Solo texto: los binarios (jar del wrapper, imágenes) se saltan.
    show "$f" | grep -Iq . 2>/dev/null || continue
    file_patterns=("${patterns[@]}")
    [[ "$f" =~ \.(kt|kts|java|xml)$ ]] || file_patterns+=("$kv_pattern")
    for p in "${file_patterns[@]}"; do
        if hits=$(show "$f" | grep -nE -e "$p" | grep -v 'secret-check: ignore'); then
            # Solo el número de línea: no se repite el secreto en la terminal.
            echo "Posible secreto en $f, líneas: $(cut -d: -f1 <<<"$hits" | paste -sd, -)"
            found=1
        fi
    done
done

if (( found )); then
    echo "Revisá esas líneas antes de commitear (o marcalas con 'secret-check: ignore' si son de prueba)."
    exit 1
fi
echo "Sin secretos a la vista (${#files[@]} archivos revisados)."
