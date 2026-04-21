import sys, json, urllib.request

def main():
    aid = int(sys.argv[1]) if len(sys.argv) > 1 else 2
    base = "http://127.0.0.1/api/v1"
    # login
    req = urllib.request.Request(
        base + "/users/login",
        data=json.dumps({"username": "admin", "password": "Yyanyyan@666"}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    r = urllib.request.urlopen(req, timeout=30)
    tk = json.loads(r.read().decode("utf-8"))["data"]["token"]
    print("token OK len", len(tk))
    # refresh
    req = urllib.request.Request(
        f"{base}/creators/accounts/{aid}/refresh:xhs",
        data=json.dumps({"limit": 20, "dryRun": False}).encode("utf-8"),
        headers={"Authorization": "Bearer " + tk, "Content-Type": "application/json"},
        method="POST",
    )
    try:
        r = urllib.request.urlopen(req, timeout=180)
        body = r.read().decode("utf-8")
        print("REFRESH status", r.status)
        print(body)
    except urllib.error.HTTPError as e:
        print("REFRESH ERR", e.code)
        print(e.read().decode("utf-8", "replace"))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
