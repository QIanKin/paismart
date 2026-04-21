UPDATE creator_accounts SET
  followers=24864,
  following=125,
  likes=100034,
  verified=0,
  avatar_url='https://sns-avatar-qc.xhscdn.com/avatar/1040g2jo3142t7n68ic005nv45abg8brgdsc75j8?imageView2/2/w/540/format/webp',
  display_name='小张真不熬夜了',
  updated_at=NOW(6)
WHERE id=2;

UPDATE creator_accounts a
JOIN (
  SELECT account_id,
         COUNT(*) c,
         CAST(AVG(likes) AS UNSIGNED) al,
         CAST(AVG(comments) AS UNSIGNED) ac,
         (AVG(likes)+AVG(comments)+AVG(COALESCE(collects,0))+AVG(COALESCE(shares,0)))/24864 er
  FROM creator_posts
  WHERE account_id=2 AND platform_post_id NOT LIKE 'mock-note%'
  GROUP BY account_id
) s ON s.account_id=a.id
SET a.avg_likes=s.al, a.avg_comments=s.ac, a.engagement_rate=s.er, a.posts=s.c;

SELECT id,display_name,followers,following,likes,posts,avg_likes,avg_comments,ROUND(engagement_rate*100,2) engage_pct,verified,updated_at FROM creator_accounts WHERE id=2\G
