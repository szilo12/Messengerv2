# 📞 Híváshangok Beállítása / Call Ringtones Setup

Ebbe a mappába helyezheted el a saját mp3 hínhangjaidat, amelyeket a Web és az Android APK alkalmazás automatikusan le fog játszani híváskor.

## Fájlok helye és neve:

1. **Bejövő hívás csörgése (Ringtone):**
   * **Név:** `ringtone.mp3`
   * **Elérési út:** `/public/assets/ringtone.mp3` (vagy a szerveren `assets/ringtone.mp3`)

2. **Kimenő hívás kicsengése (Dialtone / Ringback):**
   * **Név:** `dialtone.mp3`
   * **Elérési út:** `/public/assets/dialtone.mp3` (vagy a szerveren `assets/dialtone.mp3`)

---

## 💡 Hogyan működik a csörgés?

- **Hibrid audio-motor:** Az alkalmazás először megpróbálja betölteni és lejátszani az itt megadott `ringtone.mp3` vagy `dialtone.mp3` fájlokat.
- **Automatikus szintetizátoros tartalék (Fallback):** Ha még nem töltöttél fel fájlokat, vagy a böngésző biztonsági szempontból blokkolná a külső hangfájl betöltését (mielőtt a felhasználó rákattintana az oldalra), az alkalmazás automatikusan generál egy professzionális ütemes rezgő csörgő hangot (Web Audio API oscilátorral), hogy a hívás **garantáltan hallható legyen**!
- **Kihangosítás támogatás:** A beszélgetés közben a hívás panelen lévő **Kihangosítás** gombbal változtathatod a hangerőt és a kimenetet.
