package com.nexara.voicenotes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Тесты кодека обработчиков: сериализация/десериализация JSON.
 *
 * Чистая логика без Android — устойчивость к повреждённому JSON,
 * пустым полям, null, корректность round-trip.
 */
public class ProcessorCodecTest {

    @Test
    public void emptyListSerializesToEmptyArray() {
        assertEquals("[]", ProcessorCodec.serialize(new ArrayList<>()));
    }

    @Test
    public void nullListSerializesToEmptyArray() {
        assertEquals("[]", ProcessorCodec.serialize(null));
    }

    @Test
    public void deserializeEmptyReturnsEmpty() {
        assertTrue(ProcessorCodec.deserialize("").isEmpty());
        assertTrue(ProcessorCodec.deserialize(null).isEmpty());
        assertTrue(ProcessorCodec.deserialize("[]").isEmpty());
    }

    @Test
    public void roundTripPreservesFields() {
        List<Processor> in = Arrays.asList(
            new Processor(1, "Р¤РёРЅР°РЅР°Р»РёС‚РёРє", "С‚С‹ С„РёРЅР°РЅСЃРѕРІС‹Р№ Р°РЅР°Р»РёС‚РёРє"),
            new Processor(2, "РљСЂРёС‚РёРє", "С‚С‹ СЃС‚СЂРѕРіРёР№ РєСЂРёС‚РёРє"));
        String json = ProcessorCodec.serialize(in);
        List<Processor> out = ProcessorCodec.deserialize(json);

        assertEquals(2, out.size());
        assertEquals(1, out.get(0).id);
        assertEquals("Р¤РёРЅР°РЅР°Р»РёС‚РёРє", out.get(0).name);
        assertEquals("С‚С‹ С„РёРЅР°РЅСЃРѕРІС‹Р№ Р°РЅР°Р»РёС‚РёРє", out.get(0).prompt);
        assertEquals(2, out.get(1).id);
        assertEquals("РљСЂРёС‚РёРє", out.get(1).name);
    }

    @Test
    public void nullFieldsBecomeEmpty() {
        Processor p = new Processor();
        p.id = 5;
        p.name = null;
        p.prompt = null;
        List<Processor> out = ProcessorCodec.deserialize(ProcessorCodec.serialize(Arrays.asList(p)));
        assertEquals(1, out.size());
        assertEquals(5, out.get(0).id);
        assertEquals("", out.get(0).name);
        assertEquals("", out.get(0).prompt);
    }

    @Test
    public void corruptedJsonReturnsEmpty() {
        assertTrue(ProcessorCodec.deserialize("{ broken").isEmpty());
        assertTrue(ProcessorCodec.deserialize("not json at all").isEmpty());
        assertTrue(ProcessorCodec.deserialize("{\"obj\":1}").isEmpty());
    }

    @Test
    public void missingFieldsDefaultSafely() {
        // РѕР±СЉРµРєС‚ Р±РµР· name/prompt РЅРµ РґРѕР»Р¶РµРЅ РІР°Р»РёС‚СЊ РїР°СЂСЃРµСЂ
        List<Processor> out = ProcessorCodec.deserialize("[{\"id\":7}]");
        assertEquals(1, out.size());
        assertEquals(7, out.get(0).id);
        assertEquals("", out.get(0).name);
        assertEquals("", out.get(0).prompt);
    }

    @Test
    public void roundTripPreservesColor() {
        List<Processor> in = Arrays.asList(
            new Processor(1, "РљСЂРёС‚РёРє", "x", 0xFFFF6D00),
            new Processor(2, "РЎРїР°СЃРµСЂ", "y", 0));
        List<Processor> out = ProcessorCodec.deserialize(ProcessorCodec.serialize(in));
        assertEquals(0xFFFF6D00, out.get(0).color);
        assertEquals(0, out.get(1).color); // default 0 для старых записей
    }

    @Test
    public void missingColorDefaultsToZero() {
        // старый JSON без поля color — не должен валить
        List<Processor> out = ProcessorCodec.deserialize("[{\"id\":1,\"name\":\"A\",\"prompt\":\"x\"}]");
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).color);
    }

    @Test
    public void colorForIndexWraps() {
        int n = Processor.CLOUD_COLORS.length;
        assertEquals(Processor.CLOUD_COLORS[0], Processor.colorForIndex(0));
        assertEquals(Processor.CLOUD_COLORS[0], Processor.colorForIndex(n));
        assertEquals(Processor.CLOUD_COLORS[1], Processor.colorForIndex(n + 1));
        assertEquals(Processor.CLOUD_COLORS[n - 1], Processor.colorForIndex(-1));
    }

    @Test
    public void getByIdFindsMatch() {
        List<Processor> procs = ProcessorCodec.deserialize(
            ProcessorCodec.serialize(Arrays.asList(
                new Processor(10, "Рђ", "x"),
                new Processor(20, "Р‘", "y"))));
        Processor found = null;
        for (Processor p : procs) if (p.id == 20) found = p;
        assertNotNull(found);
        assertEquals("Р‘", found.name);
    }

    @Test
    public void getByIdMissesCleanly() {
        List<Processor> procs = ProcessorCodec.deserialize(
            ProcessorCodec.serialize(Arrays.asList(new Processor(10, "Рђ", "x"))));
        Processor found = null;
        for (Processor p : procs) if (p.id == 999) found = p;
        assertNull(found);
    }
}
