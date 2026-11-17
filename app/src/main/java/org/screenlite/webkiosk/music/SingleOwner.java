package org.screenlite.webkiosk.music;

/** 音楽を鳴らす持ち主をアプリ全体で1つに保つ（画面が2つできても音楽が二重に鳴らない）。 */
public final class SingleOwner<T> {
    private T owner;

    /** o を持ち主にする。前の持ち主が o と別ならそれを返す（呼び出し側が止める）。同じなら null。 */
    public synchronized T claim(T o) {
        T prev = owner;
        owner = o;
        return prev == o ? null : prev;
    }

    /** 今の持ち主が o のときだけ空にする。 */
    public synchronized void release(T o) {
        if (owner == o) owner = null;
    }

    public synchronized T current() { return owner; }
}
