DELETE FROM creator_posts WHERE platform_post_id LIKE 'mock%';
UPDATE creator_accounts SET bio=NULL WHERE id=2 AND bio LIKE '%???%';
SELECT COUNT(*) AS real_notes FROM creator_posts WHERE account_id=2;
