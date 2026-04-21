"""Read creator_accounts and related rows via API so we see real UTF-8."""
import json, sys, urllib.request


def main():
    base = "http://127.0.0.1/api/v1"
    # login
    r = urllib.request.urlopen(urllib.request.Request(
        base + "/users/login",
        data=json.dumps({"username": "admin", "password": "Yyanyyan@666"}).encode(),
        headers={"Content-Type": "application/json"}, method="POST"), timeout=20)
    tk = json.loads(r.read().decode())["data"]["token"]
    H = {"Authorization": "Bearer " + tk}

    r = urllib.request.urlopen(urllib.request.Request(
        base + "/creators/accounts?page=0&size=5", headers=H), timeout=20)
    j = json.loads(r.read().decode())
    print("=== creator_accounts page ===")
    for it in j.get("data", {}).get("items", []):
        keep = {k: it[k] for k in (
            "id", "platform", "handle", "displayName", "followers", "following", "likes",
            "posts", "avgLikes", "avgComments", "engagementRate", "verified",
            "categoryMain", "region", "avatarUrl", "bio") if k in it}
        print(json.dumps(keep, ensure_ascii=False, indent=2))

    r = urllib.request.urlopen(urllib.request.Request(
        base + "/creators?page=0&size=5", headers=H), timeout=20)
    j = json.loads(r.read().decode())
    print("\n=== creators page ===")
    for it in j.get("data", {}).get("items", []):
        keep = {k: it[k] for k in ("id", "displayName", "realName", "cooperationStatus") if k in it}
        print(json.dumps(keep, ensure_ascii=False, indent=2))

    r = urllib.request.urlopen(urllib.request.Request(
        base + "/creators/accounts/2/posts?page=0&size=3", headers=H), timeout=20)
    j = json.loads(r.read().decode())
    print("\n=== creator_posts (account=2) ===")
    for it in j.get("data", {}).get("items", []):
        keep = {k: it[k] for k in ("platformPostId", "title", "likes", "comments", "collects", "publishedAt") if k in it}
        print(json.dumps(keep, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
