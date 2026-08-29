SELECT "account"."account_id" AS "account__account_id", "account"."owner_email" AS "account__owner_email", "account"."state" AS "account__state" FROM "accounts" "account" WHERE ("account"."state" = $1) AND (((("account"."account_id" >= $2) AND ("account"."account_id" <= $3)) OR ("account"."account_id" = $4)) AND (NOT ("account"."owner_email" IS NULL)))
$1 = ACTIVE
$2 = 1
$3 = 2
$4 = 3
