"""Crea el archivo htpasswd (bcrypt) de los usuarios de prueba. Solo para el Radicale local."""
import os
import sys

import bcrypt

# Credenciales de prueba, únicamente para 127.0.0.1. Se pueden cambiar con variables de entorno.
USERS = {
    os.environ.get("RADICALE_TEST_USER", "test"): os.environ.get("RADICALE_TEST_PASSWORD", "local-test-pass"),  # secret-check: ignore
    "other": "other-test-pass",  # secret-check: ignore
}

path = sys.argv[1]
os.makedirs(os.path.dirname(path), exist_ok=True)
with open(path, "w") as f:
    for user, password in USERS.items():
        f.write(f"{user}:{bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10)).decode()}\n")
os.chmod(path, 0o600)
