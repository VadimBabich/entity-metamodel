SELECT "account"."account_id" AS "account__account_id", "account"."owner_email" AS "account__owner_email", "account"."state" AS "account__state" FROM "accounts" "account" WHERE ("account"."account_id" > $1) AND (("account"."owner_email" LIKE $2) OR ("account"."owner_email" LIKE $3))
$1 = 0
$2 = %first%
$3 = %second%
