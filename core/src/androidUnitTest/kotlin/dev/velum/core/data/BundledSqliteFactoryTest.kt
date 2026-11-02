package dev.velum.core.data

import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Android framework SQLite has no FTS5; the bundled build is used only when its native lib loads. */
class BundledSqliteFactoryTest {
    @Test fun bundled_factory_is_used_when_the_native_library_loads() {
        assertIs<RequerySQLiteOpenHelperFactory>(BundledSqlite.factoryOrNull(isRobolectric = false, load = {}))
    }

    @Test fun missing_native_library_falls_back_to_the_framework_helper() {
        assertNull(BundledSqlite.factoryOrNull(isRobolectric = false, load = { throw UnsatisfiedLinkError("synthetic") }))
    }

    @Test fun robolectric_always_uses_the_framework_helper_without_loading() {
        assertNull(BundledSqlite.factoryOrNull(isRobolectric = true, load = { error("must not load under Robolectric") }))
    }
}
