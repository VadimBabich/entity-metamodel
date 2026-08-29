SELECT COUNT(1) FROM "memberships" "membership" JOIN "accounts" "account" ON "membership"."account_id" = "account"."account_id" LEFT OUTER JOIN "accounts" "account_sponsor" ON "membership"."sponsor_account_id" = "account_sponsor"."account_id" JOIN "access_grants" "accessgrant" ON (("membership"."account_id" = "accessgrant"."account_id") AND ("accessgrant"."principal_id" = $1)) AND ("accessgrant"."has_browse_access" = $2)
$1 = 4711
$2 = true
