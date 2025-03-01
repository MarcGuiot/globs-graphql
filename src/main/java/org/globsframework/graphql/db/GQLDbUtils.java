package org.globsframework.graphql.db;

import org.globsframework.core.functional.FunctionalKey;
import org.globsframework.core.metamodel.fields.StringField;
import org.globsframework.core.model.Glob;
import org.globsframework.core.utils.collections.MultiMap;
import org.globsframework.graphql.OnKey;
import org.globsframework.sql.SelectQuery;
import org.globsframework.sql.SqlConnection;
import org.globsframework.sql.constraints.Constraint;
import org.globsframework.sql.constraints.Constraints;

import java.util.List;
import java.util.stream.Stream;

public class GQLDbUtils {
    public interface Cached {
        Cached NULL = new Cached() {
            public Glob get(FunctionalKey functionalKey) {
                return null;
            }

            public void push(FunctionalKey functionalKey, Glob glob) {

            }
        };

        Glob get(FunctionalKey functionalKey);

        void push(FunctionalKey functionalKey, Glob glob);
    }

    public static void queryByKey(SqlConnection db, StringField fKeyField, StringField dbKeyField, List<OnKey> parents,
                                  Constraint additionalConstraint) {
        queryByKey(db, fKeyField, dbKeyField, parents, additionalConstraint, Cached.NULL);
    }

    public static void queryByKey(SqlConnection db, StringField fKeyField, StringField dbKeyField,
                                  List<OnKey> parents, Constraint additionalConstraint, Cached cached) {
        MultiMap<String, OnKey> keyOnLoadMap = new MultiMap<>();
        for (OnKey parent : parents) {
            final Glob glob = cached.get(parent.key());
            if (glob != null) {
                parent.onNew().push(glob);
            } else {
                keyOnLoadMap.put(parent.key().get(fKeyField), parent);
            }
        }
        if (keyOnLoadMap.isEmpty()) {
            return;
        }
        final Constraint in = Constraints.in(dbKeyField, keyOnLoadMap.keySet());
        try (SelectQuery query = db.getQueryBuilder(dbKeyField.getGlobType(), Constraints.and(additionalConstraint, in))
                .selectAll()
                .getQuery()) {
            try (Stream<Glob> globStream = query.executeAsGlobStream()) {
                globStream.forEach(glob -> {
                    final String s = glob.get(dbKeyField);
                    final List<OnKey> onKeys = keyOnLoadMap.get(s);
                    for (OnKey onKey : onKeys) {
                        cached.push(onKey.key(), glob);
                        onKey.onNew().push(glob);
                    }
                });
            }
        }
    }
}
