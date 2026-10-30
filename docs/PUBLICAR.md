# Publicar una versión

Pasos para publicar a mano. Nada de esto lo hace ningún script del repo.

## Antes de publicar

1. Revisar que OkHttp 5.3.2 y Okio 3.16.4 no tengan vulnerabilidades conocidas
   ([osv.dev](https://osv.dev/list?ecosystem=Maven&q=com.squareup.okhttp3)).
2. Tests y lint:
   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
   scripts/radicale-test.sh start
   ./gradlew :core:test :core:integrationTest :app:lintDebug
   scripts/radicale-test.sh stop
   ```
3. Secretos en todo el historial: `scripts/check-secrets.sh --history`.
4. Emails de los commits: `git log --format='%ae%n%ce' | sort -u` (solo el noreply de GitHub).

## Compilar y firmar

La clave está en `~/.android-keys/liquid-calendar.jks` y sus datos en
`~/.android-keys/liquid-calendar.properties`, fuera del repo. Hay que guardar una copia de los
dos en un lugar seguro: sin esa clave no se pueden publicar actualizaciones que Android acepte
sobre la versión instalada.

```bash
./gradlew clean :app:assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/liquid-calendar-vX.Y.Z.apk
cd dist && sha256sum liquid-calendar-vX.Y.Z.apk > liquid-calendar-vX.Y.Z.apk.sha256
apksigner verify --print-certs liquid-calendar-vX.Y.Z.apk   # huella SHA-256 del certificado
aapt2 dump permissions liquid-calendar-vX.Y.Z.apk
```

El SHA-256 del APK va en `README.md` y `README.es.md`. La huella del certificado no cambia entre
versiones.

## Subir a GitHub

```bash
gh repo create mrsalvaa/liquid-calendar --public --source . --description "Free CalDAV calendar for Android with a liquid-glass interface"
# o, si el repo ya existe:
git remote add origin git@github.com:mrsalvaa/liquid-calendar.git
git push -u origin main
git tag -a vX.Y.Z -m "Liquid Calendar X.Y.Z"
git push origin vX.Y.Z
gh release create vX.Y.Z dist/liquid-calendar-vX.Y.Z.apk dist/liquid-calendar-vX.Y.Z.apk.sha256 \
  --title "Liquid Calendar X.Y.Z" --notes-file <(awk '/^## X.Y.Z/{f=1;next} /^## /{f=0} f' CHANGELOG.md)
```
