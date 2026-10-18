package dev.riichicraft.persistence;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** Deterministic Windows sharing-violation regression; no real sleep or external files. */
public final class ArchiveMoveTests {
    private static int assertions;
    public static void main(String[] args) {
        assertions=0;
        try { verify(); } catch(Exception failure) { throw new AssertionError("Atomic move retry verification failed",failure); }
        System.out.println("PASS atomic archive replacement: "+assertions+" assertions");
    }
    private static void verify() throws Exception {
        Path base=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path dir=Files.createTempDirectory(base,"riichicraft-move-");
        check(dir.startsWith(base),"Fixture remains in project temp directory");
        try {
            Path target=dir.resolve("tables.dat"), pending=dir.resolve("tables.pending");
            byte[] original=TableArchiveStore.encode(List.of());
            var seats=IntStream.range(0,4).mapToObj(i->new TableArchiveStore.Seat(UUID.randomUUID(),"Seat"+i)).toList();
            byte[] replacement=TableArchiveStore.encode(List.of(new TableArchiveStore.Table(UUID.randomUUID(),"minecraft:overworld",
                    1,2,3,0,1,1,seats,new byte[]{1},List.of(0,1,2,3))));
            Files.write(target,original);Files.write(pending,replacement);
            int[] calls={0},waits={0};
            TableArchiveStore.replaceAtomically(pending,target,(source,destination)->{
                if(++calls[0]<=3) throw new AccessDeniedException(destination.toString());
                Files.move(source,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            },millis->{check(millis==25,"Only bounded short pauses");waits[0]++;});
            check(calls[0]==4 && waits[0]==3,"Transient denial recovers within its bounded atomic attempts");
            check(Arrays.equals(Files.readAllBytes(target),replacement) && !Files.exists(pending),"Only successful atomic move replaces original");
            Files.write(target,original);Files.write(pending,replacement);calls[0]=0;waits[0]=0;
            expect(AccessDeniedException.class,()->TableArchiveStore.replaceAtomically(pending,target,(s,t)->{
                calls[0]++;throw new AccessDeniedException(t.toString());},ms->waits[0]++));
            check(calls[0]==5 && waits[0]==4,"Permanent denial stops after at most 100 ms of configured waiting");
            check(Arrays.equals(Files.readAllBytes(target),original) && Files.exists(pending),"Permanent denial preserves original bytes");
            calls[0]=0;waits[0]=0;
            expect(IOException.class,()->TableArchiveStore.replaceAtomically(pending,target,(s,t)->{
                calls[0]++;throw new AtomicMoveNotSupportedException(s.toString(),t.toString(),"test");},ms->waits[0]++));
            check(calls[0]==1 && waits[0]==0 && Arrays.equals(Files.readAllBytes(target),original),"No non-atomic fallback or unsupported retries");
            expect(IOException.class,()->TableArchiveStore.replaceAtomically(pending,target,(s,t)->{
                throw new AccessDeniedException(t.toString());},ms->{throw new InterruptedException("test");}));
            check(Thread.interrupted(),"Interruption is preserved for the caller");
            check(Arrays.equals(Files.readAllBytes(target),original),"Interrupted wait cannot overwrite original");
            calls[0]=0;byte[] damaged=original.clone();damaged[0]^=1;
            expect(IOException.class,()->TableArchiveStore.replaceAtomically(pending,target,(s,t)->{
                calls[0]++;throw new AccessDeniedException(t.toString());},ms->{
                    try {Files.write(target,damaged);} catch(IOException failure) {throw new AssertionError(failure);}
                }));
            check(calls[0]==1 && Arrays.equals(Files.readAllBytes(target),damaged),"A record damaged during waiting is revalidated and never overwritten");
        } finally {
            Thread.interrupted();
            try(var paths=Files.walk(dir)) {for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);}
        }
    }
    private interface Action {void run() throws Exception;}
    private static void expect(Class<? extends Throwable> type,Action action) throws Exception {
        try {action.run();} catch(Throwable failure) {check(type.isInstance(failure),"Expected "+type.getSimpleName());return;}
        throw new AssertionError("Expected failure "+type.getSimpleName());
    }
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(message);}
}
