-- Idempotent schema setup for station-derived bulk-scan groups.
alter table public.ccocs
    add column if not exists group_id text,
    add column if not exists group_qr_id uuid;

with group_ids as (
    select upper(left(btrim(station), 7)) as group_id,
           coalesce(min(group_qr_id::text)::uuid, gen_random_uuid()) as group_qr_id
    from public.ccocs
    where station ~* '^ED' and length(btrim(station)) >= 7
    group by upper(left(btrim(station), 7))
)
update public.ccocs as c
set group_id = g.group_id,
    group_qr_id = g.group_qr_id
from group_ids as g
where c.station ~* '^ED'
  and length(btrim(c.station)) >= 7
  and upper(left(btrim(c.station), 7)) = g.group_id
  and (c.group_id is distinct from g.group_id or c.group_qr_id is distinct from g.group_qr_id);

update public.ccocs
set group_id = null, group_qr_id = null
where station is null or station !~* '^ED' or length(btrim(station)) < 7;

create index if not exists ccocs_group_id_idx on public.ccocs (group_id);
create index if not exists ccocs_group_qr_id_idx on public.ccocs (group_qr_id);

create or replace function public.sync_ccocs_group_fields()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
    existing_qr_id uuid;
begin
    if new.station ~* '^ED' and length(btrim(new.station)) >= 7 then
        new.group_id := upper(left(btrim(new.station), 7));
        select c.group_qr_id
        into existing_qr_id
        from public.ccocs as c
        where c.group_id = new.group_id and c.group_qr_id is not null
        limit 1;
        new.group_qr_id := coalesce(existing_qr_id, new.group_qr_id, gen_random_uuid());
    else
        new.group_id := null;
        new.group_qr_id := null;
    end if;
    return new;
end;
$$;

drop trigger if exists ccocs_sync_group_fields on public.ccocs;
create trigger ccocs_sync_group_fields
before insert or update of station on public.ccocs
for each row execute function public.sync_ccocs_group_fields();

revoke all on function public.sync_ccocs_group_fields() from public, anon, authenticated;

comment on column public.ccocs.group_id is
    'First seven station characters when station starts with ED; otherwise null.';
comment on column public.ccocs.group_qr_id is
    'Opaque shared UUID used in secured bulk-scan QR payloads.';
