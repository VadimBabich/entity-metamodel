SELECT "membership"."membership_id" AS "membership__membership_id", "membership"."account_id" AS "membership__account_id", "membership"."sponsor_account_id" AS "membership__sponsor_account_id" FROM "memberships" "membership" JOIN "access_grants" "accessgrant" ON (("membership"."account_id" = "accessgrant"."account_id") AND ("accessgrant"."principal_id" = $1)) AND ("accessgrant"."has_browse_access" = $2) JOIN "access_grants" "accessgrant_licence" ON (("accessgrant_licence"."account_id" = COALESCE("membership"."sponsor_account_id", "membership"."account_id")) AND ("accessgrant_licence"."principal_id" = $3)) AND ("accessgrant_licence"."has_browse_access" = $4)
$1 = 4711
$2 = true
$3 = 4711
$4 = true
