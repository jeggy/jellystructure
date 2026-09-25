#!/usr/bin/env python3
"""Phase 258 — compare every ravilo_device row's five policy fields with Jellyfin's live /Users policy.

Usage: scripts/check-device-policy-drift.py <jellystructure.db> <jellyfin_url> <jellyfin_token>

Reads the DB read-only (copy it first when it is prod's: `sqlite3 ... ".backup ..."`), normalises the live
policy exactly as RaviloDeviceService.loginDevice stores it (GUIDs without dashes and lowercased, tags
lowercased, EnableAllFolders => unrestricted, MaxParentalRating != null => kids) and prints one line per
row that disagrees. Exit 0 when every row agrees, 1 when any drifts, 2 when Jellyfin did not answer.
This is the acceptance script of specs/requirements/phase-258-device-policy-follows-jellyfin.md.
"""
import json, sqlite3, sys, urllib.request

def norm_guid(g): return g.replace("-", "").lower()
def split(raw): return set(x for x in (raw or "").split(",") if x)

def main():
    if len(sys.argv) != 4:
        print(__doc__); return 2
    db_path, url, token = sys.argv[1:4]
    req = urllib.request.Request(url.rstrip("/") + "/Users", headers={"Authorization": f'MediaBrowser Client="jellystructure", Device="check-device-policy-drift", DeviceId="check-device-policy-drift", Version="258", Token="{token}"', "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            users = json.load(r)
    except Exception as e:
        print(f"Jellyfin did not answer /Users: {e}"); return 2
    live = {}
    for u in users:
        p = u.get("Policy", {})
        live[u["Id"].replace("-", "").lower()] = dict(
            name=u.get("Name"),
            libraries=None if p.get("EnableAllFolders", True) else {norm_guid(x) for x in p.get("EnabledFolders", [])},
            allowed={t.lower() for t in p.get("AllowedTags", [])},
            blocked={t.lower() for t in p.get("BlockedTags", [])},
            admin=bool(p.get("IsAdministrator", False)),
            kids=p.get("MaxParentalRating") is not None,
        )
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    # policy_refreshed_at arrives with migration 49; a pre-258 DB (the "before" run) has no such column.
    rows = con.execute("SELECT device_id, jellyfin_user_id, jellyfin_username, display_name, allowed_libraries, allowed_tags, blocked_tags, is_admin, is_kids FROM ravilo_device").fetchall()
    drift = 0; missing = 0
    for dev, uid, uname, dname, libs, allowed, blocked, admin, kids in rows:
        lp = live.get(uid.replace("-", "").lower())
        if lp is None:
            missing += 1; print(f"?  {uname:12} {dname:28} user not in /Users (left alone by FR-258-3)"); continue
        stored = dict(libraries=None if libs is None else split(libs), allowed=split(allowed), blocked=split(blocked), admin=admin == 1, kids=kids == 1)
        diffs = [k for k in ("libraries", "allowed", "blocked", "admin", "kids") if stored[k] != lp[k]]
        if diffs:
            drift += 1
            for k in diffs:
                print(f"!  {uname:12} {dname:28} {k}: row={fmt(stored[k])} jellyfin={fmt(lp[k])}")
    print(f"{len(rows)} rows · {drift} drifted · {missing} without a live user")
    return 1 if drift else 0

def fmt(v):
    if v is None: return "all"
    if isinstance(v, set): return "{" + ",".join(sorted(x[:8] for x in v)) + "}" if v else "{}"
    return str(v)

if __name__ == "__main__":
    sys.exit(main())
