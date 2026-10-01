-- DRAFT — NOT applied to the live database. Waiting for the owner's go-ahead.

-- =====================================================================
-- 23. Back to the v1.1.35 behaviour (owner's decision): the UPI app's
--     SUCCESS answer activates the plan automatically. Guard rails:
--     the answer must name this order (txnRef = order number) when the
--     app echoes it, must carry a transaction ID, that ID works once,
--     the amount comes from the order, and it must arrive within 2 hours.
--     Anything else (no answer, SUBMITTED, PENDING) keeps the order open
--     for "Mark as paid" / a bank status source.
-- =====================================================================
create or replace function public.apply_verified_payment(p_payment_id uuid, p_amount numeric, p_utr text, p_provider_txn text, p_source text, p_bank_credit uuid)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  pay public.payments;
  utr text := nullif(upper(regexp_replace(coalesce(p_utr, ''), '\s', '', 'g')), '');
  ptx text := nullif(upper(regexp_replace(coalesce(p_provider_txn, ''), '\s', '', 'g')), '');
begin
  if p_source not in ('bank_credit', 'admin', 'provider_api', 'upi_app') then raise exception 'Unknown verification source'; end if;
  select * into pay from public.payments where id = p_payment_id for update;
  if not found then raise exception 'Order not found'; end if;
  if pay.status = 'approved' then return pay; end if;
  if pay.status not in ('initiated', 'pending', 'failed', 'cancelled') then
    raise exception 'This order can no longer be paid (status %)', pay.status;
  end if;
  if p_amount is null or round(p_amount, 2) <> pay.amount then
    raise exception 'Amount mismatch: paid %, expected %', p_amount, pay.amount;
  end if;
  if coalesce(utr, ptx) is not null and exists (
       select 1 from public.payments where id <> pay.id and upper(txn_id) = coalesce(utr, ptx)) then
    raise exception 'This transaction has already been used';
  end if;
  update public.payments
     set status = 'approved', txn_id = coalesce(utr, ptx, txn_id), verified_at = now(), updated_at = now(),
         verification_source = p_source, bank_credit_id = coalesce(p_bank_credit, bank_credit_id),
         method = case when p_source = 'upi_app' then 'upi_auto' else method end,
         reviewed_at = now(),
         reviewed_by = case when p_source = 'admin' then coalesce(auth.jwt() ->> 'email', 'admin')
                            when p_source = 'upi_app' then 'auto (UPI app)'
                            else 'verified: ' || p_source end
   where id = pay.id
  returning * into pay;
  perform public.grant_plan_for_payment(pay);
  return pay;
end $$;
revoke execute on function public.apply_verified_payment(uuid, numeric, text, text, text, uuid) from public, anon, authenticated;
grant execute on function public.apply_verified_payment(uuid, numeric, text, text, text, uuid) to service_role;

create or replace function public.report_upi_result(p_payment_id uuid, p_response text)
returns public.payments language plpgsql security definer set search_path = public as $$
declare
  f   jsonb := public.parse_upi_response(p_response);
  st  text := lower(coalesce(f ->> 'status', ''));
  txn text;
  pay public.payments;
begin
  if auth.uid() is null then raise exception 'Please sign in first'; end if;
  update public.payments
     set upi_response  = left(p_response, 2000),
         client_status = nullif(st, ''),
         updated_at    = now(),
         status        = case when st in ('failure', 'failed') then 'failed' else status end,
         reviewed_at   = case when st in ('failure', 'failed') then now() else reviewed_at end,
         reviewed_by   = case when st in ('failure', 'failed') then 'UPI app: payment failed' else reviewed_by end
   where id = p_payment_id and user_id = auth.uid() and status = 'initiated'
  returning * into pay;
  if not found then
    select * into pay from public.payments where id = p_payment_id and user_id = auth.uid();
    if not found then raise exception 'Order not found'; end if;
    return pay;
  end if;

  if st = 'success' then
    if nullif(f ->> 'txnref', '') is not null and upper(f ->> 'txnref') <> upper(pay.reference) then
      raise exception 'This payment response belongs to a different order';
    end if;
    txn := upper(regexp_replace(coalesce(nullif(f ->> 'approvalrefno', ''), nullif(f ->> 'txnid', ''), ''), '\s', '', 'g'));
    if txn !~ '^[A-Z0-9]{6,40}$' then raise exception 'The UPI app did not return a transaction ID'; end if;
    if pay.created_at < now() - interval '2 hours' then
      raise exception 'This payment has expired or was already processed. Please start again.';
    end if;
    return public.apply_verified_payment(pay.id, pay.amount, txn, null, 'upi_app', null);
  end if;
  return public.try_verify_payment(pay.id);
end $$;
revoke execute on function public.report_upi_result(uuid, text) from public, anon;
grant execute on function public.report_upi_result(uuid, text) to authenticated;
