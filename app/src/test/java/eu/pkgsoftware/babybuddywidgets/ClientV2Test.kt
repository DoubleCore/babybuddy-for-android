package eu.pkgsoftware.babybuddywidgets

import com.fasterxml.jackson.databind.ObjectMapper
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.BmiEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.ChangeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.FeedingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeadCircumferenceEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.HeightEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.NoteEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.PumpingEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.SleepEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TemperatureEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.TummyTimeEntry
import eu.pkgsoftware.babybuddywidgets.networking.babybuddy.models.WeightEntry
import org.junit.Test

class ClientV2Test {
    @Test
    fun sleepObjectSerialization() {
        val s: String = """
        {
            "id": 269,
            "child": 1,
            "start": "2023-10-13T00:07:08.351000+02:00",
            "end": "2023-10-13T00:07:12.107094+02:00",
            "duration": "00:00:03.756094",
            "nap": false,
            "notes": "",
            "tags": []
        }"""

        val mapper = ObjectMapper()
        val sleepEntry: SleepEntry = mapper.readValue(s, SleepEntry::class.java)
    }

    @Test
    fun tummyTimeObjectSerialization() {
        val s: String = """
        {
            "id": 230,
            "child": 1,
            "start": "2023-10-13T00:07:20.216000+02:00",
            "end": "2023-10-13T00:07:29.496101+02:00",
            "duration": "00:00:09.280101",
            "milestone": "Pierre is nice",
            "tags": []
        }"""

        val mapper = ObjectMapper()
        val tummyTime: TummyTimeEntry = mapper.readValue(s, TummyTimeEntry::class.java)
    }

    @Test
    fun feedingObjectSerialization() {
        val s: String = """
        {
            "id": 223,
            "child": 1,
            "start": "2023-10-13T00:07:10.249000+02:00",
            "end": "2023-10-13T00:07:16.967648+02:00",
            "duration": "00:00:06.718648",
            "type": "breast milk",
            "method": "right breast",
            "amount": null,
            "notes": "",
            "tags": []
        }"""

        val mapper = ObjectMapper()
        val feeding: FeedingEntry = mapper.readValue(s, FeedingEntry::class.java)
    }

    @Test
    fun pumpingObjectSerialization() {
        val s: String = """
        {
            "id": 2,
            "child": 1,
            "amount": 0.0,
            "start": "2023-10-24T23:49:40+02:00",
            "end": "2023-10-24T23:55:40+02:00",
            "duration": "00:06:00",
            "notes": "",
            "tags": [
                "Listened to Music"
            ]
        }"""

        val mapper = ObjectMapper()
        val pumping: PumpingEntry = mapper.readValue(s, PumpingEntry::class.java)
    }

    @Test
    fun changeObjectSerialization() {
        val s: String = """
        {
            "id": 404,
            "child": 1,
            "time": "2023-08-31T23:51:27+02:00",
            "wet": true,
            "solid": false,
            "color": "",
            "amount": null,
            "notes": "",
            "tags": [
               "Happy"
            ]
        }"""

        val mapper = ObjectMapper()
        val change: ChangeEntry = mapper.readValue(s, ChangeEntry::class.java)
    }

    @Test
    fun noteObjectSerialization() {
        val s: String = """
        {
            "id": 3,
            "child": 1,
            "note": "Hello",
            "time": "2022-05-26T16:53:00+02:00",
            "tags": [
                "Holla"
            ]
        }"""

        val mapper = ObjectMapper()
        val note: NoteEntry = mapper.readValue(s, NoteEntry::class.java)
    }

    @Test
    fun bmiObjectSerialization() {
        val s: String = """
        {
            "id": 5,
            "child": 1,
            "bmi": 11.18,
            "date": "2022-03-05",
            "notes": "Number sit seven note.",
            "tags": []
        }"""

        val mapper = ObjectMapper()
        val bmi: BmiEntry = mapper.readValue(s, BmiEntry::class.java)
    }

    @Test
    fun temperatureObjectSerialization() {
        val s: String = """
        {
            "id": 78,
            "child": 1,
            "temperature": 101.96,
            "time": "2022-03-07T21:09:00+01:00",
            "notes": "Why special keep bag song nice lawyer strategy.",
            "tags": [
                "4re"
            ]
        }"""

        val mapper = ObjectMapper()
        val temperature: TemperatureEntry = mapper.readValue(s, TemperatureEntry::class.java)
    }

    @Test
    fun weightObjectSerialization() {
        val s: String = """
        {
            "id": 5,
            "child": 1,
            "weight": 12.9,
            "date": "2022-03-05",
            "notes": "",
            "tags": [
                "a tag"
            ]
        }"""

        val mapper = ObjectMapper()
        val weight: WeightEntry = mapper.readValue(s, WeightEntry::class.java)
    }

    @Test
    fun heightObjectSerialization() {
        val s: String = """
        {
            "id": 5,
            "child": 1,
            "height": 9.43,
            "date": "2022-03-05",
            "notes": "",
            "tags": []
        }"""

        val mapper = ObjectMapper()
        val height: HeightEntry = mapper.readValue(s, HeightEntry::class.java)
    }

    @Test
    fun headCircumferenceObjectSerialization() {
        val s: String = """
        {
            "id": 5,
            "child": 1,
            "head_circumference": 12.12,
            "date": "2022-03-05",
            "notes": "",
            "tags": [
                "Holla"
            ]
        }"""

        val mapper = ObjectMapper()
        val headcircumference: HeadCircumferenceEntry = mapper.readValue(s, HeadCircumferenceEntry::class.java)
    }

    @Test
    fun growthAndTemperatureWriteSerialization() {
        val kMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val sampleDate = eu.pkgsoftware.babybuddywidgets.networking.babybuddy.parseNullOrDate("2026-05-10", "yyyy-MM-dd")!!
        val sampleDateTime = eu.pkgsoftware.babybuddywidgets.networking.babybuddy.parseNullOrDate("2026-05-10T14:30:00+08:00", "yyyy-MM-dd'T'HH:mm:ssX")!!

        val weightNode = kMapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(
            WeightEntry(id = 0, childId = 2, start = sampleDate, _notes = "checkup", weight = 6.45)
        ).apply { remove("id") }
        org.junit.Assert.assertEquals(2, weightNode.get("child").asInt())
        org.junit.Assert.assertEquals("2026-05-10", weightNode.get("date").asText())
        org.junit.Assert.assertEquals(6.45, weightNode.get("weight").asDouble(), 0.001)
        org.junit.Assert.assertEquals("checkup", weightNode.get("notes").asText())
        org.junit.Assert.assertNull(weightNode.get("_notes"))
        org.junit.Assert.assertNull(weightNode.get("end"))

        val heightNode = kMapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(
            HeightEntry(id = 0, childId = 2, start = sampleDate, _notes = "", height = 63.5)
        ).apply { remove("id") }
        org.junit.Assert.assertEquals("2026-05-10", heightNode.get("date").asText())
        org.junit.Assert.assertEquals(63.5, heightNode.get("height").asDouble(), 0.001)

        val hcNode = kMapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(
            HeadCircumferenceEntry(id = 0, childId = 2, start = sampleDate, _notes = null, head_circumference = 41.2)
        ).apply { remove("id") }
        org.junit.Assert.assertEquals(41.2, hcNode.get("head_circumference").asDouble(), 0.001)
        org.junit.Assert.assertEquals("", hcNode.get("notes").asText())

        val bmiNode = kMapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(
            BmiEntry(id = 0, childId = 2, start = sampleDate, _notes = null, bmi = 16.0)
        ).apply { remove("id") }
        org.junit.Assert.assertEquals(16.0, bmiNode.get("bmi").asDouble(), 0.001)

        val tempNode = kMapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(
            TemperatureEntry(id = 0, childId = 2, start = sampleDateTime, _notes = "normal", temperature = 36.8)
        ).apply { remove("id") }
        org.junit.Assert.assertEquals(36.8, tempNode.get("temperature").asDouble(), 0.001)
        org.junit.Assert.assertTrue(tempNode.get("time").asText().startsWith("2026-05-10T"))
        org.junit.Assert.assertNull(tempNode.get("_notes"))
        org.junit.Assert.assertNull(tempNode.get("end"))
    }
}
