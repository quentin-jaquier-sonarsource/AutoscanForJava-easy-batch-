/*
 * The MIT License
 *
 *  Copyright (c) 2015, Mahmoud Ben Hassine (mahmoud@benhassine.fr)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

package org.easybatch.json;

import org.easybatch.core.api.RecordReader;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonValue;
import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonGeneratorFactory;
import javax.json.stream.JsonParser;
import java.io.*;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Record reader that reads Json records from an array of Json objects:
 *
 * [
 *  {
 *      // JSON object
 *  },
 *  {
 *      // JSON object
 *  }
 * ]
 *
 * @author Mahmoud Ben Hassine (mahmoud@benhassine.fr)
 */
public class JsonRecordReader implements RecordReader {

    private static final Logger LOGGER = Logger.getLogger(JsonRecordReader.class.getSimpleName());

    /**
     * The data source stream.
     */
    private InputStream inputStream;

    /**
     * The json parser used to read the json stream.
     */
    private JsonParser parser;

    /**
     * The Json generator factory.
     */
    private JsonGeneratorFactory jsonGeneratorFactory;

    /**
     * The current record number.
     */
    private int currentRecordNumber;

    private JsonParser.Event currentEvent;

    private JsonParser.Event nextEvent;

    private int arrayDepth;

    private int objectDepth;

    private String key;

    public JsonRecordReader(InputStream inputStream) {
        this.inputStream = inputStream;
        this.jsonGeneratorFactory = Json.createGeneratorFactory(new HashMap<String, Object>());
    }

    @Override
    public void open() throws Exception {
        parser = Json.createParser(inputStream);
    }

    @Override
    public boolean hasNextRecord() {
        if(parser.hasNext()) {
            currentEvent = parser.next();
            if (JsonParser.Event.START_ARRAY.equals(currentEvent)) {
                arrayDepth++;
            }
            if (JsonParser.Event.END_ARRAY.equals(currentEvent)) {
                arrayDepth--;
            }
            if (JsonParser.Event.KEY_NAME.equals(currentEvent)) {
                key = parser.getString();
            }
        }

        if(parser.hasNext()) {
            nextEvent = parser.next();
            if (JsonParser.Event.START_ARRAY.equals(nextEvent)) {
                arrayDepth++;
            }
            if (JsonParser.Event.END_ARRAY.equals(nextEvent)) {
                arrayDepth--;
            }
            if (JsonParser.Event.KEY_NAME.equals(nextEvent)) {
                key = parser.getString();
            }
        }
        if (JsonParser.Event.START_ARRAY.equals(currentEvent) && JsonParser.Event.END_ARRAY.equals(nextEvent) && arrayDepth == 0) {
            return false;
        }
        if (JsonParser.Event.END_ARRAY.equals(currentEvent) && arrayDepth == 1 && objectDepth == 0) {
            return false;
        }
        return true;
    }

    @Override
    public JsonRecord readNextRecord() throws Exception {
        StringWriter stringWriter = new StringWriter();
        JsonGenerator jsonGenerator = jsonGeneratorFactory.createGenerator(stringWriter);
        writeRecordStart(jsonGenerator);
        do {
            moveToNextElement(jsonGenerator);
        } while(!isEndRootObject());
        if (arrayDepth != 2) {
            jsonGenerator.writeEnd();
        }
        jsonGenerator.close();
        return new JsonRecord(++currentRecordNumber, stringWriter.toString());
    }

    @Override
    public Integer getTotalRecords() {
        //Unable to use the same (or even another) json parser to calculate total record number of the input stream.
        int data;
        int objectDepth = 0;
        int totalRecords = 0;
        try {
            data = inputStream.read();
            while(data != -1) {
                char currentChar = (char)data;
                if('{' == currentChar) {
                    objectDepth++;
                }
                if('}' == currentChar) {
                    objectDepth--;
                    if (objectDepth == 0) {
                        totalRecords++;
                    }
                }
                data = inputStream.read();
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to calculate total records number in JSON stream.", e);
            return null;
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Unable to close JSON stream when calculating total records number.", e);
            }
        }
        return totalRecords;
    }

    @Override
    public String getDataSourceName() {
        return "Json stream: " + inputStream;
    }

    @Override
    public void close() throws Exception {
        parser.close();
    }

    private boolean isEndRootObject() {
        return objectDepth == 0;
    }

    private void writeRecordStart(JsonGenerator jsonGenerator) {
        if (currentEvent.equals(JsonParser.Event.START_ARRAY)) {
            if(arrayDepth != 1) {
                jsonGenerator.writeStartArray();
            }
            arrayDepth++;
        }
        if (currentEvent.equals(JsonParser.Event.START_OBJECT)) {
            jsonGenerator.writeStartObject();
            objectDepth++;
        }
        if (nextEvent.equals(JsonParser.Event.START_ARRAY)) {
            jsonGenerator.writeStartArray();
            arrayDepth++;
        }
        if (nextEvent.equals(JsonParser.Event.START_OBJECT)) {
            jsonGenerator.writeStartObject();
            objectDepth++;
        }
    }

    private void moveToNextElement(JsonGenerator jsonGenerator) {
        JsonParser.Event event = parser.next();
        switch(event) {
            case START_ARRAY:
                jsonGenerator.writeStartArray();
                break;
            case END_ARRAY:
                jsonGenerator.writeEnd();
                break;
            case START_OBJECT:
                objectDepth++;
                jsonGenerator.writeStartObject();
                break;
            case END_OBJECT:
                objectDepth--;
                jsonGenerator.writeEnd();
                break;
            case VALUE_FALSE:
                jsonGenerator.write(key, JsonValue.FALSE);
                break;
            case VALUE_NULL:
                jsonGenerator.write(key, JsonValue.NULL);
                break;
            case VALUE_TRUE:
                jsonGenerator.write(key, JsonValue.TRUE);
                break;
            case KEY_NAME:
                key = parser.getString();
                break;
            case VALUE_STRING:
                jsonGenerator.write(key, parser.getString());
                break;
            case VALUE_NUMBER:
                jsonGenerator.write(key, parser.getBigDecimal());
                break;
        }
    }

}