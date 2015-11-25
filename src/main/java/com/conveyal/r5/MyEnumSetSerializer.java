package com.conveyal.r5;

import org.nustaq.serialization.FSTBasicObjectSerializer;
import org.nustaq.serialization.FSTClazzInfo;
import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;

import java.io.IOException;
import java.util.EnumSet;

/**
 * Created by mabu on 25.11.2015.
 */
public class MyEnumSetSerializer extends FSTBasicObjectSerializer {

    /**
     * @return true if FST can skip a search for same instances in the serialized ObjectGraph. This speeds up reading and writing and makes
     *         sense for short immutable such as Integer, Short, Character, Date, .. . For those classes it is more expensive (CPU, size) to do a lookup than to just
     *         write the Object twice in case.
     */
    @Override
    public boolean alwaysCopy() {
        return false;
    }

    /**
     * write the contents of a given object
     *
     * @param out
     * @param toWrite
     * @param clzInfo
     * @param referencedBy
     * @param streamPosition
     */
    @Override public void writeObject(FSTObjectOutput out, Object toWrite, FSTClazzInfo clzInfo,
        FSTClazzInfo.FSTFieldInfo referencedBy, int streamPosition) throws IOException {
        EnumSet enset = (EnumSet) toWrite;
        EnumSet compl = EnumSet.complementOf(enset);
        out.writeBoolean(enset.size() > 0);
        if ( enset.isEmpty() ) { //WTF only way to determine enumtype ..
            out.writeClassTag(compl.iterator().next().getClass());
        } else {
            out.writeClassTag(compl.iterator().next().getClass());
            int flag = getBitFlag(enset);
            out.writeInt(flag);
        }
    }


    public <T extends Enum<T>> int getBitFlag(EnumSet<T> enumType) {
        int flag = 0;
        for (T c : enumType) {
            flag |= (1 << c.ordinal());
        }
        return flag;
    }

    public <T extends Enum<T>> EnumSet<T> fromBitFlag(Class<T> enumType, int flags) {
        EnumSet<T> enumSet = EnumSet.noneOf(enumType);
        for (T c : enumType.getEnumConstants()) {
            if ((flags & (1 << c.ordinal())) != 0) {
                enumSet.add(c);
            }
        }
        return enumSet;
    }

    @Override public Object instantiate(Class objectClass, FSTObjectInput in,
        FSTClazzInfo serializationInfo, FSTClazzInfo.FSTFieldInfo referencee, int streamPosition)
        throws Exception {
        byte len = in.readByte();
        Class elemCL = in.readClass().getClazz();
        if (len > 0) {
            return fromBitFlag(elemCL, in.readInt());
        } else {
            return EnumSet.noneOf(elemCL);
        }
    }
}
