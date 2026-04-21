SELECT platform_post_id, LENGTH(title) AS len, title
  FROM creator_posts
 WHERE account_id=2 AND platform_post_id NOT LIKE 'mock%'
 ORDER BY published_at DESC LIMIT 8;
