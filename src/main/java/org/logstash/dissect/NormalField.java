package org.logstash.dissect;

import com.logstash.Event;

import java.util.Map;

class NormalField implements Field {
    private int _ordinal;
    protected final String _name;

    protected Delim _join;
    protected Delim _next;

    private static final Field missing = new NormalField("missing field", 100000);

    public static Field create(String s) {
        return new NormalField(s);
    }

    public static Field getMissing() {
        return missing;
    }

    public NormalField(String s) {
        this(s, 1);
    }

    protected NormalField(String s, int ord) {
        _name = s;
        _ordinal = ord;
    }

    protected static String leftChop(String s) {
        return s.substring(1);
    }

    @Override
    public boolean saveable() {
        return true;
    }

    @Override
    public void append(Map<String, Object> map, ValueResolver values) {
        map.put(_name, values.get(this));
    }

    @Override
    public void append(Event event, ValueResolver values) {
      event.setField(_name, values.get(this));
    }

    @Override
    public void addPreviousDelim(Delim d) {
        _join = d;
    }

    @Override
    public void addNextDelim(Delim d) {
        _next = d;
    }

    @Override
    public String name() {
        return _name;
    }

    @Override
    public int ordinal() {
        return _ordinal;
    }

    @Override
    public int previousDelimSize() {
        if (_join == null) {
            return 0;
        }
        return _join.size();
    }

    public String joinString() {
        if (_join == null) {
            return " ";
        }
        return _join.string();
    }

    @Override
    public Delim delimiter() {
        return _next;
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("NormalField{");
        sb.append("name=").append(_name);
        sb.append(", ordinal=").append(_ordinal);
        sb.append(", join=").append(_join);
        sb.append(", delimiter=").append(_next);
        sb.append('}');
        return sb.toString();
    }
}