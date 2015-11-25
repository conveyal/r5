package com.conveyal.r5;

import org.nustaq.serialization.FSTClazzInfo;
import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;
import org.nustaq.serialization.serializers.FSTArrayListSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;

public class MyEnumSetListSerializer extends FSTArrayListSerializer {

    @Override
    public void writeObject(FSTObjectOutput out, Object toWrite, FSTClazzInfo clzInfo, FSTClazzInfo.FSTFieldInfo referencedBy, int streamPosition) throws
        IOException {
        ArrayList col = (ArrayList)toWrite;
        int size = col.size();
        if (col.get(0).getClass().getSimpleName().equals("RegularEnumSet")) {
            //System.out.println("EnumSet");
            EnumSet compl = EnumSet.complementOf((EnumSet)col.get(0));
            int[] enums = new int[col.size()];
            for (int i = 0; i < size; i++) {
                EnumSet o = (EnumSet) col.get(i);
                int flag = getBitFlag(o);
                enums[i] = flag;
            }
            out.writeBoolean(true);
            out.writeClassTag(compl.iterator().next().getClass());
            out.writeObjectInternal(enums, null, null);
        } else {
            out.writeBoolean(false);
            super.writeObject(out, toWrite, clzInfo, referencedBy, streamPosition);
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

    @Override
    public Object instantiate(Class objectClass, FSTObjectInput in, FSTClazzInfo serializationInfo, FSTClazzInfo.FSTFieldInfo referencee, int streamPosition) throws Exception {
        boolean isEnumSetList = in.readBoolean();
        if (isEnumSetList) {
            Class elemCL = in.readClass().getClazz();
            int[] enums = (int[]) in.readObjectInternal(null);
            ArrayList res = new ArrayList(enums.length);
            for (int i = 0; i < enums.length; i++) {
                res.add(fromBitFlag(elemCL, enums[i]));
            }
            return res;
        } else {
            return super.instantiate(objectClass, in, serializationInfo, referencee, streamPosition);
        }
    }

}

