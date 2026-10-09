package org.alter.data.wiki

import kotlin.test.Test
import kotlin.test.assertEquals

class BucketQueryTests {
    @Test
    fun `builds the documented query shape`() {
        val query = BucketQuery("dropsline").select("page_name", "drop_json").where("page_name", "Abyssal demon").orderBy("page_name_sub").page(5000, 10000)
        assertEquals(
            "bucket('dropsline').select('page_name','drop_json').where('page_name','Abyssal demon').orderBy('page_name_sub','asc').limit(5000).offset(10000).run()",
            query.build(),
        )
    }

    @Test
    fun `joins and categories`() {
        val query = BucketQuery("infobox_bonuses").join("infobox_item", "infobox_item.page_name_sub", "infobox_bonuses.page_name_sub")
            .select("infobox_item.item_id").whereCategory("Pets").page(10, 0)
        assertEquals(
            "bucket('infobox_bonuses').join('infobox_item','infobox_item.page_name_sub','infobox_bonuses.page_name_sub')" +
                ".select('infobox_item.item_id').where('Category:Pets').limit(10).offset(0).run()",
            query.build(),
        )
    }

    @Test
    fun `quotes and backslashes are escaped`() {
        assertEquals(
            "bucket('x').where('page_name','Larran\\'s key \\\\ test').limit(1).offset(0).run()",
            BucketQuery("x").where("page_name", "Larran's key \\ test").page(1, 0).build(),
        )
    }
}
