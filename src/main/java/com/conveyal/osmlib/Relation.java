package com.conveyal.osmlib;

import com.google.common.collect.Lists;

import java.io.Serializable;
import java.util.List;

public class Relation extends OSMEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    public List<Member> members = Lists.newArrayList();

    public static class Member implements Serializable {

        private static final long serialVersionUID = 1L;
        public OSMEntity.Type type;
        public long id;
        public String role;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(type.toString());
            sb.append(' ');
            sb.append(id);
            if (role != null && !role.isEmpty()) {
                sb.append(" as ");
                sb.append(role);
            }
            return sb.toString();
        }

        @Override
        public boolean equals (Object other) {
            if (!(other instanceof Member)) return false;
            Member otherMember = (Member) other;
            return this.type == otherMember.type &&
                   this.id == otherMember.id &&
                   this.role.equals(otherMember.role);
        }

    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("relation with tags ");
        sb.append(tags);
        sb.append('\n');
        for (Member member : members) {
            sb.append("  ");
            sb.append(member.toString());
            sb.append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        if ( ! (other instanceof Relation)) return false;
        Relation otherRelation = (Relation) other;
        return this.members.equals(otherRelation.members) && this.tagsEqual(otherRelation);
    }

    @Override
    public Type getType() {
        return Type.RELATION;
    }

}
