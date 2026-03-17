# new-migration

Scaffold the next Flyway migration file for the Budgeteer project.

## Steps

1. Check `backend/src/main/resources/db/migration/` to find the highest existing version number.
2. The next migration file should be `V{n+1}__{description}.sql` where `{description}` is derived from the user's argument or ask the user what the migration does if no argument was given.
3. Create the file at `backend/src/main/resources/db/migration/V{n+1}__{description}.sql`.
4. Scaffold appropriate SQL based on what the user described. Follow these conventions:
   - Use `UUID` primary keys
   - Include `created_at TIMESTAMP WITH TIME ZONE` on new tables
   - Use `REFERENCES {table}(id) ON DELETE CASCADE` for FKs where appropriate
   - Add relevant indexes (unique constraints, foreign key indexes, query-path indexes)
   - Use lowercase SQL keywords style consistent with existing migrations
5. Remind the user: **never modify existing migrations**, and to test with `cd backend && mvn test -Dgroups=integration` before committing.
