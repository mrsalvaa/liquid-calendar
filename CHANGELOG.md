# Changelog

**English** | [Español](CHANGELOG.es.md)

## Unreleased

### New
- Agenda and widget: events happening right now show up at the top, under "Now", followed by the rest (today, tomorrow…). The widget updates itself when an event starts or ends.

### Changed
- The agenda shows two weeks from the chosen date, without "Show more".
- When creating an event, the calendar picker shows the colour, the name and a phone or cloud icon instead of the "Synced with…" text.

## 1.2.0

### New
- Interface style: besides Liquid Glass (the default, with all its usual options) there is "Solid", with opaque cards on a plain background and no glass effects. It is plainer: less rounded corners, rectangular buttons, outline icons and a docked bottom bar. It has light or dark theme and its own accent colour.
- You can give the server a name when connecting. It is shown instead of the address, which can be long.

### Improved
- In day view, when an event is very short on screen (hours compressed), its time goes to the right of the title instead of below it.

## 1.1.1

### Improved
- Pinching in week and day views no longer jumps when the fingers cross: it stays at the smallest size. The height follows the distance between your fingers since the start of the gesture.
- When the hours are compressed, the hour column shows every other hour (00, 02, 04…); the rest appear as you expand.
- Hour numbers are centred on their line.

### Changed
- Week and day titles no longer show the year: "26/10 – 01/11" and "Friday 30/10". The month title is unchanged.

## 1.1.0

### New
- Agenda widget for the home screen: today and the next 13 days, grouped by day, with the app's look (light or dark theme, accent colour). Tap an event to open it, or "+" to create one for today.

## 1.0.0

First release.

### New
- Month, week, day and agenda views. In week and day, pinch to change the height of the hours.
- Calendars that live only on the device, and an optional CalDAV account (tested with Radicale 3.8).
- Create, edit and delete events. In a series: just this one, this and the following ones, or all of them, with a summary before confirming.
- Reminders as local notifications, private on the lock screen by default. Optional exact timing.
- Works offline: changes wait in a queue and never overwrite a newer version on the server. Conflicts show both versions and let you choose.
- Create, rename, recolor and delete calendars on the server. Deleting asks you to type the calendar's name and offers to export it first.
- If a calendar disappears from the server, nothing is deleted: you choose to keep it on the device, export it or discard it.
- Read-only webcal/ICS subscriptions that stay on the device, refreshed every 1 h, 6 h, 12 h, day or week, or only by hand.
- Import and export `.ics` files (whole calendar or a single event) through the system file picker.
- Liquid-glass interface shared with Liquid Clock and Liquid Gallery: plain, moving or accent-colour background, light and dark theme, and alternative app icons.

### Security and privacy
- HTTPS only, with the system's certificate authorities. User-installed certificates are not trusted.
- The password is encrypted with a key kept in the Android Keystore.
- The first wrong password (401) stops every request, in the foreground and in the background, until you type it again.
- No telemetry, no Google services, no storage permissions, and no backups.
